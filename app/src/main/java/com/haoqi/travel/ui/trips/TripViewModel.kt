package com.haoqi.travel.ui.trips

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.haoqi.travel.data.importer.MdDocument
import com.haoqi.travel.data.importer.MdExporter
import com.haoqi.travel.data.importer.MdParser
import com.haoqi.travel.data.importer.MdPlace
import com.haoqi.travel.data.importer.MdTicket
import com.haoqi.travel.data.local.entity.PlaceEntity
import com.haoqi.travel.data.local.entity.PlaceType
import com.haoqi.travel.data.local.entity.PlanItemEntity
import com.haoqi.travel.data.local.entity.PlanSlot
import com.haoqi.travel.data.local.entity.ProfileEntity
import com.haoqi.travel.data.local.entity.TicketEntity
import com.haoqi.travel.data.local.entity.TicketType
import com.haoqi.travel.data.local.entity.TripEntity
import com.haoqi.travel.data.remote.AiClient
import com.haoqi.travel.data.remote.AiConfig
import com.haoqi.travel.data.remote.AiSettings
import com.haoqi.travel.data.remote.GeocodeHelper
import com.haoqi.travel.data.reminder.ReminderManager
import com.haoqi.travel.data.reminder.ReminderReceiver
import com.haoqi.travel.data.repository.TravelRepository
import com.haoqi.travel.ui.profile.PlanSpec
import com.haoqi.travel.ui.profile.autoTripName
import com.haoqi.travel.ui.profile.buildPlanBrief
import com.haoqi.travel.ui.profile.buildRefinePrompt
import com.haoqi.travel.ui.profile.buildSearchBrief
import com.haoqi.travel.ui.profile.resolveCities
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.time.LocalDate
import java.util.LinkedHashMap

/** AI 规划流程所处阶段：填表单 / 对话改稿 */
enum class PlanPhase { FORM, CHAT }

/** 对话里的一条消息 */
data class ChatMsg(val fromUser: Boolean, val text: String)

/** 「AI 规划」页的完整状态（草稿存在内存里，App 被杀即清空） */
data class AiPlanState(
    val phase: PlanPhase = PlanPhase.FORM,
    val running: Boolean = false,
    val importing: Boolean = false,
    val startDate: String = LocalDate.now().toString(),
    val days: Int = 3,
    val homeCity: String = "",
    val cities: List<String> = List(3) { "" },
    val messages: List<ChatMsg> = emptyList(),
    val currentMd: String = "",
    val currentDoc: MdDocument? = null,
    val error: String? = null,
    /** 联网搜索的状态提示（成功/回退原因），空串表示不显示 */
    val searchNote: String = "",
    /** 联网核实到的真实车票/酒店事实清单（Markdown），用于展示 + 第二步规划引用 */
    val facts: String = "",
    /** 表单里的特殊要求备注 */
    val note: String = "",
)

class TripViewModel(private val repo: TravelRepository) : ViewModel() {

    val trips: StateFlow<List<TripEntity>> = repo.observeTrips()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _activeTripId = MutableStateFlow<Long?>(null)
    val activeTripId: StateFlow<Long?> = _activeTripId.asStateFlow()

    val activeTrip: StateFlow<TripEntity?> =
        combine(_activeTripId, trips) { id, list -> list.firstOrNull { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val places: StateFlow<List<PlaceEntity>> = _activeTripId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repo.observePlaces(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val tickets: StateFlow<List<TicketEntity>> = _activeTripId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repo.observeTickets(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val plan: StateFlow<List<PlanItemEntity>> = _activeTripId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repo.observePlan(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val profile: StateFlow<ProfileEntity?> = repo.observeProfile()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _planState = MutableStateFlow(AiPlanState())
    val planState: StateFlow<AiPlanState> = _planState.asStateFlow()

    init {
        viewModelScope.launch {
            repo.observeTrips().collect { list ->
                val current = _activeTripId.value
                _activeTripId.value = when {
                    current == null -> list.firstOrNull()?.id
                    list.none { it.id == current } -> list.firstOrNull()?.id
                    else -> current
                }
            }
        }
    }

    // ---- 旅行 ----
    fun setActiveTrip(id: Long) {
        _activeTripId.value = id
    }

    fun deleteTrip(context: Context, trip: TripEntity) {
        viewModelScope.launch {
            try {
                // 先取消该旅行下所有已排好的本地提醒，避免删除后仍弹通知
                repo.observeTickets(trip.id).first().forEach { ReminderManager.cancelTicket(context, it) }
                repo.observePlaces(trip.id).first().forEach { ReminderManager.cancelReservation(context, it) }
                repo.deleteTrip(trip)
            } catch (e: Exception) {
                // 删除失败时也不要崩溃；激活旅行切换交给 init 里的 trips 监听处理
            }
        }
    }

    // ---- 地点 ----
    fun addPlace(
        context: Context,
        name: String,
        type: PlaceType,
        city: String,
        address: String,
        latitude: Double?,
        longitude: Double?,
        note: String = "",
        needReservation: Boolean = false,
        reservationDate: String? = null,
        bookingInfo: String = "",
    ) {
        val tripId = _activeTripId.value ?: return
        viewModelScope.launch {
            val place = PlaceEntity(
                tripId = tripId,
                name = name,
                type = type,
                city = city,
                address = address,
                latitude = latitude,
                longitude = longitude,
                note = note,
                needReservation = needReservation,
                reservationDate = reservationDate,
                bookingInfo = bookingInfo,
            )
            val id = repo.addPlace(place)
            if (needReservation) {
                ReminderManager.scheduleReservation(
                    context,
                    activeTrip.value?.startDate.orEmpty(),
                    place.copy(id = id),
                )
            }
        }
    }

    // ---- 车票 ----
    fun addTicket(
        context: Context,
        type: TicketType,
        trainNo: String,
        from: String,
        to: String,
        departureTime: Long,
        seat: String,
    ) {
        val tripId = _activeTripId.value ?: return
        viewModelScope.launch {
            val ticket = TicketEntity(
                tripId = tripId,
                type = type,
                trainNo = trainNo,
                fromStation = from,
                toStation = to,
                departureTime = departureTime,
                seat = seat,
                remindMinutesBefore = defaultRemindMinutes(type),
            )
            val id = repo.addTicket(ticket)
            ReminderManager.scheduleTicket(context, ticket.copy(id = id))
        }
    }

    fun deleteTicket(context: Context, ticket: TicketEntity) {
        viewModelScope.launch {
            repo.deleteTicket(ticket)
            ReminderManager.cancelTicket(context, ticket)
        }
    }

    /**
     * 保存改过的车票。把 AI 的「交通建议」落实成真实车票（填上车次和发车时间）时也走这里，
     * 保存后重新排一次出发提醒。
     */
    fun saveTicket(context: Context, ticket: TicketEntity) {
        viewModelScope.launch {
            ReminderManager.cancelTicket(context, ticket)
            repo.updateTicket(ticket)
            ReminderManager.scheduleTicket(context, ticket)
        }
    }

    private fun defaultRemindMinutes(type: TicketType): Int =
        com.haoqi.travel.data.local.entity.defaultRemindMinutes(type)

    // ---- 日程 ----
    fun addPlaceToPlan(placeId: Long, dayIndex: Int, slot: PlanSlot) {
        val tripId = _activeTripId.value ?: return
        viewModelScope.launch {
            val items = repo.observePlan(tripId).first().toMutableList()
            val existing = items.firstOrNull { it.placeId == placeId }
            if (existing != null) {
                repo.replacePlan(
                    tripId,
                    normalize(items.map { if (it.placeId == placeId) it.copy(dayIndex = dayIndex, slot = slot, manual = true) else it }),
                )
            } else {
                items.add(
                    PlanItemEntity(tripId = tripId, dayIndex = dayIndex, slot = slot, placeId = placeId, orderIndex = 0, manual = true),
                )
                repo.replacePlan(tripId, normalize(items))
            }
        }
    }

    fun removeFromPlan(placeId: Long) {
        val tripId = _activeTripId.value ?: return
        viewModelScope.launch {
            val items = repo.observePlan(tripId).first().filter { it.placeId != placeId }
            repo.replacePlan(tripId, normalize(items))
        }
    }

    fun movePlace(placeId: Long, targetDay: Int, targetSlot: PlanSlot) {
        val tripId = _activeTripId.value ?: return
        viewModelScope.launch {
            val items = repo.observePlan(tripId).first().map {
                if (it.placeId == placeId) it.copy(dayIndex = targetDay, slot = targetSlot, manual = true) else it
            }
            repo.replacePlan(tripId, normalize(items))
        }
    }

    fun shiftPlace(placeId: Long, direction: Int) {
        val tripId = _activeTripId.value ?: return
        viewModelScope.launch {
            val list = repo.observePlan(tripId).first().toMutableList()
            val idx = list.indexOfFirst { it.placeId == placeId }
            if (idx < 0) return@launch
            val target = idx + direction
            if (target < 0 || target >= list.size) return@launch
            if (list[idx].dayIndex != list[target].dayIndex || list[idx].slot != list[target].slot) return@launch
            val tmp = list[idx]
            list[idx] = list[target]
            list[target] = tmp
            repo.replacePlan(tripId, normalize(list))
        }
    }

    private fun normalize(items: List<PlanItemEntity>): List<PlanItemEntity> {
        val groups = LinkedHashMap<Pair<Int, PlanSlot>, MutableList<PlanItemEntity>>()
        for (item in items) {
            groups.getOrPut(item.dayIndex to item.slot) { mutableListOf() }.add(item)
        }
        return groups.flatMap { (_, group) -> group.mapIndexed { i, item -> item.copy(orderIndex = i) } }
    }

    // ---- AI 规划 ----
    fun updatePlanForm(
        startDate: String? = null,
        days: Int? = null,
        homeCity: String? = null,
        cities: List<String>? = null,
        note: String? = null,
    ) {
        val s = _planState.value
        val d = days ?: s.days
        var c = cities ?: s.cities
        if (c.size != d) {
            c = List(d) { i -> c.getOrElse(i) { "" } }
        }
        _planState.value = s.copy(
            startDate = startDate ?: s.startDate,
            days = d,
            homeCity = homeCity ?: s.homeCity,
            cities = c,
            note = note ?: s.note,
            error = null,
        )
    }

    fun generatePlan(context: Context) {
        val s = _planState.value
        if (s.running) return
        if (!AiSettings.hasKey(context)) {
            _planState.value = s.copy(error = "请先在「更多 → AI 生成设置」里填写 API Key")
            return
        }
        val webSearch = AiSettings.load(context).webSearch
        val spec = PlanSpec(s.startDate, s.days, s.homeCity, s.cities, s.note)
        if (resolveCities(spec).all { it.isBlank() }) {
            _planState.value = s.copy(error = "请至少填写一天的旅行城市，AI 才知道去哪儿规划")
            return
        }
        viewModelScope.launch {
            // 立即进入聊天页显示“正在规划”，让用户有反馈，而不是盯着表单干等
            _planState.value = _planState.value.copy(
                phase = PlanPhase.CHAT,
                running = true,
                error = null,
                searchNote = "",
                messages = listOf(ChatMsg(fromUser = false, text = "正在为你规划行程，联网搜索可能稍慢，请稍候…")),
            )
            try {
                val config = AiSettings.load(context)
                // 两步法：①联网收集真实车票+酒店事实；②规划引用事实（未联网则事实为空，酒店待定/交通建议）
                var facts = ""
                var factsFailReason: String? = null
                if (webSearch) {
                    _planState.value = _planState.value.copy(
                        searchNote = "正在联网核实车票和酒店（首次约 1～3 分钟，请耐心等待）…",
                    )
                    // 联网核实：失败自动重试一次；仍失败就降级普通模式（车票只给建议、酒店待定，绝不编造），
                    // 但要把失败原因留下来告诉用户，而不是静默吞掉
                    val attempt: suspend () -> String = {
                        AiClient.chatWithWebSearch(
                            config,
                            buildSearchBrief(spec, profile.value),
                            readTimeoutMs = 170_000,
                            factsMode = true,
                        )
                    }
                    var failMsg: String? = null
                    val result = withTimeoutOrNull(200_000) {
                        try {
                            attempt()
                        } catch (e1: java.io.IOException) {
                            try {
                                attempt()
                            } catch (e2: java.io.IOException) {
                                failMsg = step1FriendlyError(e2)
                                null
                            }
                        }
                    }
                    facts = result ?: ""
                    factsFailReason = when {
                        result != null -> null
                        failMsg != null -> failMsg
                        else -> "超过 3 分钟未返回（可能网络慢或服务繁忙）"
                    }
                }
                _planState.value = _planState.value.copy(
                    searchNote = when {
                        facts.isNotBlank() -> "已联网核实到真实车票/酒店，正在生成行程…"
                        webSearch -> "联网核实失败（$factsFailReason），已改用普通模式：车票只给建议、酒店待定"
                        else -> "未联网，正在生成行程…"
                    },
                )
                val md = chatWithOneRetry(config, buildPlanBrief(spec, profile.value, facts))
                var doc = MdParser.parse(md)
                if (doc.days.none { it.items.isNotEmpty() }) {
                    throw IllegalStateException("AI 没返回完整的每日行程。请点「重新规划」再试一次；如果一直这样，请把「查看原文 MD」的内容发给我")
                }
                // 代码级兜底：把规划结果里「联网清单中没有的」车次降级为建议、酒店改为待定，杜绝幻觉
                doc = filterHallucinations(doc, facts)
                val missing = missingLegs(spec, doc)
                _planState.value = _planState.value.copy(
                    phase = PlanPhase.CHAT,
                    running = false,
                    searchNote = "",
                    currentMd = MdExporter.fromDocument(doc),
                    currentDoc = doc,
                    facts = facts,
                    messages = listOf(ChatMsg(fromUser = false, text = "已为你生成行程初稿，看看是否满意，也可以继续提修改意见。")) +
                        (factsFailReason?.let { r ->
                            listOf(
                                ChatMsg(
                                    fromUser = false,
                                    text = "⚠️ 本次没能联网核实真实车票/酒店（原因：$r）。结果里车票只是「交通建议」、酒店为「待定」，没有编造数据。" +
                                        "想拿到真实车票，请到「更多 → AI 生成设置」检查：①火山方舟已开通「联网内容插件」；" +
                                        "②模型框填的是推理接入点 ep-xxx；③网络正常，然后点「重新规划」。",
                                ),
                            )
                        }.orEmpty()) +
                        missing.takeIf { it.isNotEmpty() }?.let { list ->
                            listOf(
                                ChatMsg(
                                    fromUser = false,
                                    text = "⚠️ 本次有几段交通没生成：" + list.joinToString("、") { "${it.first}→${it.second}" } +
                                        "。可让 AI 补上（如「补一下返程交通」），或导入后在「车票」页手动添加。",
                                ),
                            )
                        }.orEmpty(),
                )
                ReminderReceiver.notifyPlanDone(context.applicationContext, "行程已生成，打开看看吧")
            } catch (e: Exception) {
                _planState.value = _planState.value.copy(running = false, error = e.message ?: "生成失败")
            }
        }
    }

    /** 根据 出发地+每天城市 算出应有的交通段，找出 AI 没生成的（有去无回之类） */
    private fun missingLegs(spec: PlanSpec, doc: MdDocument): List<Pair<String, String>> {
        val home = spec.homeCity.trim()
        val cities = resolveCities(spec).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (cities.isEmpty()) return emptyList()
        val legs = mutableListOf<Pair<String, String>>()
        if (home.isNotBlank()) legs += home to cities.first()
        for (i in 1 until cities.size) {
            if (cities[i] != cities[i - 1]) legs += cities[i - 1] to cities[i]
        }
        if (home.isNotBlank() && cities.last() != home) legs += cities.last() to home
        return legs.filter { (from, to) ->
            doc.tickets.none { t ->
                val f = t.fromStation
                val s = t.toStation
                f.isNotBlank() && s.isNotBlank() &&
                    (f.contains(from) || from.contains(f)) && (s.contains(to) || to.contains(s))
            }
        }
    }

    /** 从事实清单里提取「合法车次号集合」（G/D/C/K/T/Z/Y/L + 数字） */
    private fun extractRealTrainNos(facts: String): Set<String> {
        if (facts.isBlank()) return emptySet()
        return Regex("""[GDCKTZYL]\d{1,5}""").findAll(facts).map { it.value }.toSet()
    }

    /** 从事实清单里提取「合法酒店名集合」（- 酒店: xxx 的名称部分） */
    private fun extractRealHotelNames(facts: String): Set<String> {
        if (facts.isBlank()) return emptySet()
        return Regex("""- 酒店\s*[:：]\s*([^|]+)""").findAll(facts)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() && it != "待定" }
            .toSet()
    }

    /**
     * 代码级防幻觉兜底：
     * - 具体车票的车次号若不在联网核实清单里 → 降级为「交通建议」（清空车次号），绝不展示编造的车次；
     * - 酒店名若不在清单里 → 改成「待定」。
     * 只在清单非空（确实联网核实过）时生效；未联网时本来就只有建议/待定。
     */
    private fun filterHallucinations(doc: MdDocument, facts: String): MdDocument {
        if (facts.isBlank()) return doc
        val realNos = extractRealTrainNos(facts)
        val realHotels = extractRealHotelNames(facts)

        val tickets = doc.tickets.map { t ->
            if (!t.isSuggestion && t.trainNo.isNotBlank() && realNos.isNotEmpty() && t.trainNo !in realNos) {
                t.copy(trainNo = "", isSuggestion = true, note = "车次未联网核实，请自行查询")
            } else t
        }
        val days = doc.days.map { day ->
            day.copy(items = day.items.map { p ->
                if (p.type == PlaceType.HOTEL && realHotels.isNotEmpty() && p.name !in realHotels && p.name != "待定") {
                    p.copy(name = "待定（自行预订）", note = "")
                } else p
            })
        }
        return doc.copy(days = days, tickets = tickets)
    }

    fun sendRefine(context: Context, text: String) {
        val s = _planState.value
        val instruction = text.trim()
        if (s.running || instruction.isBlank() || s.currentMd.isBlank()) return
        val webSearch = AiSettings.load(context).webSearch
        val spec = PlanSpec(s.startDate, s.days, s.homeCity, s.cities, s.note)
        val md = s.currentMd
        viewModelScope.launch {
            _planState.value = _planState.value.copy(
                running = true,
                error = null,
                searchNote = "",
                messages = appendMsg(_planState.value.messages, ChatMsg(fromUser = true, text = instruction)),
            )
            try {
                val config = AiSettings.load(context)
                // 改稿复用第一次联网核实到的事实清单，不重新联网（快）
                val newMd = chatWithOneRetry(config, buildRefinePrompt(spec, profile.value, md, instruction, s.facts))
                val doc = MdParser.parse(newMd)
                if (doc.days.none { it.items.isNotEmpty() }) {
                    throw IllegalStateException("AI 没返回完整的每日行程。请再发一次修改意见，或点「重新规划」")
                }
                _planState.value = _planState.value.copy(
                    running = false,
                    currentMd = MdExporter.fromDocument(doc),
                    currentDoc = doc,
                    messages = appendMsg(_planState.value.messages, ChatMsg(fromUser = false, text = "已按你的要求更新行程。")),
                )
            } catch (e: Exception) {
                _planState.value = _planState.value.copy(
                    running = false,
                    error = e.message ?: "修改失败",
                    messages = appendMsg(_planState.value.messages, ChatMsg(fromUser = false, text = "抱歉，这次修改没成功：${e.message ?: "未知错误"}")),
                )
            }
        }
    }

    /**
     * 把第一步「联网核实」的异常翻译成用户能看懂的提示，直接显示在聊天里。
     */
    private fun step1FriendlyError(e: Exception): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("timed out", ignoreCase = true) || m.contains("timeout", ignoreCase = true) ->
                "接口读超时（服务端长时间没返回结果）"
            m.contains("ModelNotOpen") || m.contains("未开通该模型") ->
                "方舟提示：该模型未开通（去方舟控制台「开通管理」开通模型，或改用已创建的推理接入点 ep-xxx）"
            m.contains("plugin", ignoreCase = true) || m.contains("插件") ->
                "方舟提示：联网内容插件未开通或该模型不支持联网（去方舟控制台「开通管理 → 插件」开通「联网内容插件」）"
            m.contains("404") || m.contains("400") || m.contains("401") || m.contains("403") -> m.take(200)
            else -> m.takeIf { it.isNotBlank() } ?: "网络错误"
        }
    }

    /**
     * 模型偶尔会「只回思考文字不回行程正文」：与其直接报错，
     * 不如同样的请求立刻重试一次（只是内容抽检失败，不是网络/密钥问题才重试）。
     */
    private suspend fun chatWithOneRetry(config: AiConfig, prompt: String): String =
        try {
            AiClient.chat(config, prompt)
        } catch (e: IOException) {
            val msg = e.message.orEmpty()
            if (msg.contains("没返回行程正文") || msg.contains("返回内容为空") || msg.contains("只回了思考")) {
                AiClient.chat(config, prompt)
            } else {
                throw e
            }
        }

    fun confirmPlan(context: Context, name: String, onDone: () -> Unit) {
        val s = _planState.value
        val doc = s.currentDoc ?: return
        if (s.running || s.importing) return
        viewModelScope.launch {
            _planState.value = _planState.value.copy(importing = true, error = null)
            try {
                // 地理编码：并行 + 每个地点限时，个别失败不影响导入（只是少了坐标）
                val coords = geocodeAll(context, doc)
                val spec = PlanSpec(s.startDate, s.days, s.homeCity, s.cities)
                val tripName = name.trim().ifBlank { autoTripName(resolveCities(spec), s.days) }
                val tripId = repo.createTripFromDoc(tripName, s.startDate, doc, coords)
                _activeTripId.value = tripId

                // 排好提醒
                repo.observeTickets(tripId).first().forEach { ReminderManager.scheduleTicket(context, it) }
                repo.observePlaces(tripId).first().forEach { ReminderManager.scheduleReservation(context, s.startDate, it) }

                // 清空规划草稿，回到表单
                _planState.value = AiPlanState()
                onDone()
            } catch (e: Exception) {
                _planState.value = _planState.value.copy(importing = false, error = e.message ?: "导入失败")
            }
        }
    }

    /** 并行地理编码，每个地点最多等 8 秒，返回「文档序号 → (纬度, 经度)」 */
    private suspend fun geocodeAll(context: Context, doc: MdDocument): Map<Int, Pair<Double, Double>> {
        val entries = mutableListOf<Pair<String, MdPlace>>()
        for (day in doc.days) for (p in day.items) entries.add(day.city to p)
        if (entries.isEmpty()) return emptyMap()

        return coroutineScope {
            entries.mapIndexed { idx, (city, p) ->
                async {
                    val pt = withTimeoutOrNull(8_000) {
                        GeocodeHelper.forwardGeocode(context, geocodeQuery(city, p), city)
                    }
                    idx to (pt?.let { it.lat to it.lng })
                }
            }.map { it.await() }
                .filter { it.second != null }
                .associate { it.first to it.second!! }
        }
    }

    /** 挑一个更可能搜到坐标的查询词：占位地址用「城市 + 名称」代替 */
    private fun geocodeQuery(city: String, p: MdPlace): String {
        val addr = p.address.trim()
        val generic = addr.isEmpty() ||
            addr.contains("示例") || addr.contains("xxx", ignoreCase = true) ||
            addr == "市中心" || addr == "市中心附近" || addr == "附近" || addr == "城区"
        return if (generic) "$city ${p.name}".trim() else addr
    }

    /** 手动给某个没坐标的地点重新搜索定位（行程页「定位」按钮用） */
    fun locatePlace(context: Context, placeId: Long, onResult: (Boolean) -> Unit) {
        val tripId = _activeTripId.value
        if (tripId == null) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            val place = repo.observePlaces(tripId).first().firstOrNull { it.id == placeId }
            if (place == null) {
                onResult(false)
                return@launch
            }
            val queries = listOfNotNull(
                place.address.takeIf { it.isNotBlank() },
                "${place.city} ${place.name}".trim().takeIf { it.isNotBlank() },
                place.name.takeIf { it.isNotBlank() },
            ).distinct()
            var found: Pair<Double, Double>? = null
            for (q in queries) {
                val gp = withTimeoutOrNull(10_000) {
                    GeocodeHelper.forwardGeocode(context, q, place.city)
                }
                if (gp != null) {
                    found = gp.lat to gp.lng
                    break
                }
            }
            if (found != null) {
                repo.updatePlace(place.copy(latitude = found.first, longitude = found.second))
                onResult(true)
            } else {
                onResult(false)
            }
        }
    }

    fun resetPlan() {
        val s = _planState.value
        _planState.value = AiPlanState(
            startDate = s.startDate,
            days = s.days,
            homeCity = s.homeCity,
            cities = s.cities,
        )
    }

    private fun appendMsg(list: List<ChatMsg>, msg: ChatMsg): List<ChatMsg> = (list + msg).takeLast(12)

    // ---- 个人画像 ----
    fun saveProfile(profile: ProfileEntity) {
        viewModelScope.launch { repo.saveProfile(profile) }
    }

    class Factory(private val repo: TravelRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = TripViewModel(repo) as T
    }
}
