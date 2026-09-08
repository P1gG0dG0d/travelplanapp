package com.haoqi.travel.ui.trips

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.haoqi.travel.data.importer.MdDocument
import com.haoqi.travel.data.importer.MdExporter
import com.haoqi.travel.data.importer.MdParser
import com.haoqi.travel.data.importer.MdPlace
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
import com.haoqi.travel.data.repository.TravelRepository
import com.haoqi.travel.ui.profile.PlanSpec
import com.haoqi.travel.ui.profile.autoTripName
import com.haoqi.travel.ui.profile.buildPlanBrief
import com.haoqi.travel.ui.profile.buildRefinePrompt
import com.haoqi.travel.ui.profile.resolveCities
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
        val spec = PlanSpec(s.startDate, s.days, s.homeCity, s.cities)
        if (resolveCities(spec).all { it.isBlank() }) {
            _planState.value = s.copy(error = "请至少填写一天的旅行城市，AI 才知道去哪儿规划")
            return
        }
        viewModelScope.launch {
            _planState.value = _planState.value.copy(running = true, error = null, searchNote = "")
            try {
                val md = callAi(context, buildPlanBrief(spec, profile.value, webSearch), webSearch)
                val doc = MdParser.parse(md)
                if (doc.days.none { it.items.isNotEmpty() }) {
                    throw IllegalStateException("AI 没返回完整的每日行程（可能只回了车票/交通）。请点「重新规划」再试一次；如果一直这样，请把「查看原文 MD」的内容发给我")
                }
                val missing = missingLegs(spec, doc)
                _planState.value = _planState.value.copy(
                    phase = PlanPhase.CHAT,
                    running = false,
                    // 用重排后的规范 MD 展示/改稿，AI 夹带的任何杂音都不会再出现
                    currentMd = MdExporter.fromDocument(doc),
                    currentDoc = doc,
                    messages = listOf(ChatMsg(fromUser = false, text = "已为你生成行程初稿，看看是否满意，也可以继续提修改意见。")) +
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

    fun sendRefine(context: Context, text: String) {
        val s = _planState.value
        val instruction = text.trim()
        if (s.running || instruction.isBlank() || s.currentMd.isBlank()) return
        val webSearch = AiSettings.load(context).webSearch
        val spec = PlanSpec(s.startDate, s.days, s.homeCity, s.cities)
        val md = s.currentMd
        viewModelScope.launch {
            _planState.value = _planState.value.copy(
                running = true,
                error = null,
                searchNote = "",
                messages = appendMsg(_planState.value.messages, ChatMsg(fromUser = true, text = instruction)),
            )
            try {
                val newMd = callAi(context, buildRefinePrompt(spec, profile.value, md, instruction, webSearch), webSearch)
                val doc = MdParser.parse(newMd)
                if (doc.days.none { it.items.isNotEmpty() }) {
                    throw IllegalStateException("AI 没返回完整的每日行程（可能只回了车票/交通）。请再发一次修改意见，或点「重新规划」")
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
     * 调 AI 的统一入口。
     * 开了「联网搜索」就先试 DeepSeek 的 Responses API（自带 web_search 工具）；
     * 这是实验功能：报错或超过 150 秒超时，都自动退回普通的 /chat/completions，
     * 保证生成不会中断，同时把原因写进 searchNote 让你在界面上看到。
     */
    private suspend fun callAi(context: Context, prompt: String, webSearch: Boolean): String {
        val config = AiSettings.load(context)
        if (!webSearch) {
            _planState.value = _planState.value.copy(searchNote = "")
            return chatWithOneRetry(config, prompt)
        }
        // 联网搜索有时会一直卡在搜索上：最多等 150 秒，超时就熔断换普通模式
        val md = withTimeoutOrNull(150_000) {
            runCatching { AiClient.chatWithWebSearch(config, prompt) }.getOrNull()
        }
        if (md == null) {
            _planState.value = _planState.value.copy(
                searchNote = "联网搜索超时或没成功，已自动改用普通模式生成",
            )
            return chatWithOneRetry(config, prompt)
        }
        _planState.value = _planState.value.copy(searchNote = "已用联网搜索模式生成（车次/时刻仍请以 12306 为准）")
        return md
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
