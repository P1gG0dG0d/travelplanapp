package com.haoqi.travel.data.importer

import com.haoqi.travel.data.local.entity.PlaceEntity
import com.haoqi.travel.data.local.entity.PlaceType
import com.haoqi.travel.data.local.entity.PlanItemEntity
import com.haoqi.travel.data.local.entity.PlanSlot
import com.haoqi.travel.data.local.entity.TicketEntity
import com.haoqi.travel.data.local.entity.TicketType
import com.haoqi.travel.data.local.entity.TripEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---- 解析中间模型 ----

data class MdPlace(
    val slot: PlanSlot?, // null 表示酒店（不进日程排程）
    val type: PlaceType,
    val name: String,
    val address: String = "",
    val durationMinutes: Int? = null,
    val needReservation: Boolean = false,
    val reservationDate: String? = null,
    /** 预约/购票方式（如「微信小程序「故宫」提前7天预约」「现场购票」） */
    val bookingInfo: String = "",
    val checkIn: String? = null,
    val checkOut: String? = null,
    val pricePerPerson: Int? = null,
    val pricePerNight: Int? = null,
    val note: String = "",
)

data class MdDay(val dayIndex: Int, val city: String, val items: List<MdPlace>)

data class MdTicket(
    val type: TicketType,
    val trainNo: String,
    val fromStation: String,
    val toStation: String,
    val departureTime: Long,
    val seat: String = "",
    val arrivalTime: Long = 0L,
    /** 交通建议的补充说明（建议时段/大约时长/大约票价等） */
    val note: String = "",
    /** true = AI 的交通建议（无车次无时刻）；false = 具体车票 */
    val isSuggestion: Boolean = false,
)

data class MdDocument(
    val tripName: String,
    val startDate: String,
    val days: List<MdDay>,
    val tickets: List<MdTicket>,
)

fun placeTypeLabel(t: PlaceType): String = when (t) {
    PlaceType.ATTRACTION -> "景点"
    PlaceType.RESTAURANT -> "饭店"
    PlaceType.HOTEL -> "酒店"
}

fun planSlotLabel(s: PlanSlot): String = when (s) {
    PlanSlot.MORNING -> "上午"
    PlanSlot.LUNCH -> "午餐"
    PlanSlot.AFTERNOON -> "下午"
    PlanSlot.DINNER -> "晚餐"
    PlanSlot.EVENING -> "晚上"
}

// ---- 解析 MD ----

object MdParser {

    fun parse(text: String): MdDocument {
        val lines = text.lines()
        val (tripName, startDate) = parseYaml(lines)

        val days = mutableListOf<MdDay>()
        val tickets = mutableListOf<MdTicket>()

        var dayIndex = 1
        var dayCity = ""
        var currentItems = mutableListOf<MdPlace>()
        var hasCurrentDay = false
        var currentSlot: PlanSlot? = null
        var inTickets = false
        // 「# 交通建议」段落里的行一律按建议解析；「# 车票」段落才按具体车票解析
        var ticketSectionIsSuggestion = false

        fun flush() {
            if (hasCurrentDay && currentItems.isNotEmpty()) {
                days.add(MdDay(dayIndex, dayCity, currentItems))
                currentItems = mutableListOf()
            }
        }

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line == "---") continue

            when {
                line.startsWith("## ") -> {
                    inTickets = false
                    currentSlot = parseSlot(line)
                }

                line.startsWith("# ") -> {
                    val head = line.removePrefix("#").trim()
                    if (head.startsWith("车票") || head.startsWith("交通")) {
                        flush()
                        hasCurrentDay = false
                        inTickets = true
                        ticketSectionIsSuggestion = !head.startsWith("车票")
                    } else {
                        val (num, city) = parseDayHeader(line) ?: continue
                        flush()
                        dayIndex = num
                        dayCity = city
                        hasCurrentDay = true
                        inTickets = false
                    }
                }

                line.startsWith("- ") -> {
                    val content = line.removePrefix("- ").trim()
                    if (inTickets) {
                        parseTicketLine(content, ticketSectionIsSuggestion, startDate)?.let { tickets.add(it) }
                    } else {
                        val place = parsePlaceLine(content, currentSlot)
                        if (place != null) {
                            if (!hasCurrentDay) {
                                dayIndex = 1
                                dayCity = ""
                                hasCurrentDay = true
                            }
                            currentItems.add(place)
                        }
                    }
                }
            }
        }
        flush()

        val merged = days
            .groupBy { it.dayIndex }
            .map { (idx, list) ->
                // 同一天里名称相同的景点/饭店去重（AI 偶尔会重复推荐同一个地点）
                val deduped = list.flatMap { it.items }
                    .distinctBy { p -> "${p.type}:${p.name.trim()}" }
                MdDay(idx, list.firstOrNull()?.city.orEmpty(), deduped)
            }
            .sortedBy { it.dayIndex }

        // 同一条线路既给了具体车票、又给了交通建议时，只保留具体车票；
        // 同一条线路给了多个具体车票（AI 列了备选）时，只保留出发时间最早的那一个
        val realTickets = tickets.filter { !it.isSuggestion }
        val keptSuggestions = tickets.filter { t ->
            t.isSuggestion && realTickets.none { r -> sameRoute(r, t) } && !isSelfLoop(t)
        }
        // 两条一模一样的建议（同线路 + 同备注）只留一条；同线路但备注不同的（如不同“参考班次”）保留
        val dedupedSuggestions = mutableListOf<MdTicket>()
        keptSuggestions.forEach { t ->
            if (dedupedSuggestions.none { sameRoute(it, t) && it.note == t.note }) dedupedSuggestions.add(t)
        }
        val bestReal = mutableListOf<MdTicket>()
        realTickets.forEach { t ->
            val idx = bestReal.indexOfFirst { sameRoute(it, t) }
            if (idx < 0) {
                bestReal.add(t)
            } else if (departureKey(t) < departureKey(bestReal[idx])) {
                bestReal[idx] = t
            }
        }

        return MdDocument(tripName, startDate, merged, bestReal + dedupedSuggestions)
    }

    /** 起终点相同（如“武汉→武汉”“机场→市区”）这类自环，基本是模型噪声，去掉 */
    private fun isSelfLoop(t: MdTicket): Boolean =
        t.fromStation.isNotBlank() && t.toStation.isNotBlank() && stationMatch(t.fromStation, t.toStation)

    /** 排序键：没解析出时间的排最后，其余按出发时间 */
    private fun departureKey(t: MdTicket): Long =
        if (t.departureTime > 0) t.departureTime else Long.MAX_VALUE

    /** 两张票是否同方向同线路（站名允许「济南」对「济南东」这种包含关系） */
    private fun sameRoute(a: MdTicket, b: MdTicket): Boolean =
        stationMatch(a.fromStation, b.fromStation) && stationMatch(a.toStation, b.toStation)

    private fun stationMatch(x: String, y: String): Boolean {
        val a = x.filterNot { it.isWhitespace() }
        val b = y.filterNot { it.isWhitespace() }
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.contains(b) || b.contains(a)
    }

    private fun parseYaml(lines: List<String>): Pair<String, String> {
        var name = "未命名旅行"
        var date = LocalDate.now().toString()
        var inYaml = false
        for (line in lines) {
            val t = line.trim()
            if (t == "---") {
                if (inYaml) break
                inYaml = true
                continue
            }
            if (inYaml) {
                when {
                    t.startsWith("旅行名称") -> name = t.substringAfter(":", "").trim().ifBlank { name }
                    t.startsWith("开始日期") -> date = t.substringAfter(":", "").trim().ifBlank { date }
                }
            }
        }
        return name to date
    }

    private fun parseSlot(line: String): PlanSlot? {
        val t = line.removePrefix("##").trim()
        return when {
            t.startsWith("上午") || t.startsWith("早上") || t.startsWith("早餐") ||
                t.startsWith("早饭") || t.startsWith("早茶") || t.startsWith("早午餐") -> PlanSlot.MORNING
            t.startsWith("午餐") || t.startsWith("中午") || t.startsWith("午饭") -> PlanSlot.LUNCH
            t.startsWith("下午") || t.startsWith("下午茶") -> PlanSlot.AFTERNOON
            t.startsWith("晚餐") || t.startsWith("晚饭") -> PlanSlot.DINNER
            t.startsWith("晚上") || t.startsWith("夜晚") ||
                t.startsWith("夜宵") || t.startsWith("宵夜") -> PlanSlot.EVENING
            else -> null // 酒店等
        }
    }

    private fun parseDayHeader(line: String): Pair<Int, String>? {
        val t = line.removePrefix("#").trim()
        val num = Regex("""第\s*(\d+)\s*天""").find(t)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(?i)day\s*(\d+)""").find(t)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        val city = t.substringAfter("·", "").trim()
        return num to city
    }

    private fun parsePlaceLine(line: String, slot: PlanSlot?): MdPlace? {
        val segs = line.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        if (segs.isEmpty()) return null
        val firstName = segs[0].substringAfter(":", "").trim()
        if (firstName.isEmpty()) return null
        val firstKey = segs[0].substringBefore(":", "").trim()

        var type: PlaceType? = when (firstKey) {
            "景点", "景区", "打卡", "游玩" -> PlaceType.ATTRACTION
            "饭店", "餐厅", "美食" -> PlaceType.RESTAURANT
            "酒店", "住宿", "宾馆", "民宿" -> PlaceType.HOTEL
            else -> null
        }
        if (type == null) {
            type = when (slot) {
                PlanSlot.LUNCH, PlanSlot.DINNER, PlanSlot.EVENING -> PlaceType.RESTAURANT
                null -> PlaceType.HOTEL
                else -> PlaceType.ATTRACTION
            }
        }

        var address = ""
        var duration: Int? = null
        var needRes = false
        var resDate: String? = null
        var bookingInfo = ""
        var checkIn: String? = null
        var checkOut: String? = null
        var priceP: Int? = null
        var priceN: Int? = null
        var note = ""

        for (seg in segs.drop(1)) {
            val k = seg.substringBefore(":", "").trim()
            val v = seg.substringAfter(":", "").trim()
            if (k.isEmpty() || v.isEmpty()) continue
            when (k) {
                "地址" -> address = v
                "时长" -> duration = parseDuration(v)
                "预约" -> needRes = v == "是" || v == "需要" || v == "要" || v.equals("true", ignoreCase = true)
                "预约日期" -> resDate = v
                "预约方式", "购票", "购票方式", "购票渠道", "购票说明" -> bookingInfo = v
                "人均" -> priceP = v.filter { it.isDigit() }.toIntOrNull()
                "房价" -> priceN = v.filter { it.isDigit() }.toIntOrNull()
                "入住" -> checkIn = v
                "退房" -> checkOut = v
                "备注", "说明" -> note = v
            }
        }

        // 非酒店地点如果没识别出时段，兜底给个默认时段，避免漏导（饭店→午餐，景点→下午）
        val finalSlot = when {
            type == PlaceType.HOTEL -> null
            slot != null -> slot
            type == PlaceType.RESTAURANT -> PlanSlot.LUNCH
            else -> PlanSlot.AFTERNOON
        }

        return MdPlace(
            slot = finalSlot,
            type = type,
            name = firstName,
            address = address,
            durationMinutes = duration,
            needReservation = needRes,
            reservationDate = resDate,
            bookingInfo = bookingInfo,
            checkIn = checkIn,
            checkOut = checkOut,
            pricePerPerson = priceP,
            pricePerNight = priceN,
            note = note,
        )
    }

    /** 交通建议行里表示「方式」的键名 */
    private val SUGGESTION_KEYS = setOf("方式", "交通", "交通方式", "出行方式")

    /** 交通建议行里表示「区间」的键名 */
    private val ROUTE_KEYS = setOf("区间", "路线", "路段", "出发站→到达站")

    /** 出现这个键就说明是具体车票，不是交通建议 */
    private const val TRAIN_NO_KEY = "车次"

    private fun parseTicketLine(line: String, forceSuggestion: Boolean = false, defaultDate: String = ""): MdTicket? {
        val segs = line.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        if (segs.isEmpty()) return null

        // 「# 交通建议」段落里的行，或首段就写「方式:」的行，都按建议解析（不认车次和时刻）
        val firstKey = keyOf(segs[0])
        if (firstKey in SUGGESTION_KEYS || (forceSuggestion && firstKey != TRAIN_NO_KEY)) {
            return parseSuggestionLine(segs)
        }

        // 旧格式：具体车票「车次: G1234 | 出发站→到达站 | 2026-05-03 14:00 | 二等座 | 到达: ...」
        val trainNo = valueOf(segs[0]).ifBlank { segs[0] }

        var from = ""
        var to = ""
        if (segs.size >= 2) {
            splitRoute(segs[1])?.let {
                from = it.first
                to = it.second
            }
        }
        val departure = if (segs.size >= 3) parseDateTime(segs[2], defaultDate) else 0L

        // 第 4 段开始可能是「座位」或「到达: xxxx-xx-xx xx:xx」，逐个识别
        var seat = ""
        var arrival = 0L
        for (seg in segs.drop(3)) {
            val t = seg.trim()
            if (t.isEmpty()) continue
            when {
                t.startsWith("到达") || t.startsWith("到站") || t.startsWith("抵达") ->
                    arrival = parseDateTime(valueOf(t), defaultDate)
                seat.isBlank() -> seat = t
            }
        }

        // 没解析出发车时间的「车票」不能算真票：降级成交通建议，避免把笼统内容标成车票
        if (departure == 0L) {
            val refNo = trainNo.takeIf { it.isNotBlank() && !it.contains("→") && !it.contains(":") }
            return MdTicket(
                type = inferTicketType(trainNo),
                trainNo = "",
                fromStation = from,
                toStation = to,
                departureTime = 0L,
                seat = "",
                arrivalTime = 0L,
                note = if (refNo != null) {
                    "参考班次: $refNo（时刻未确认，请在 12306 / 航司 App 核实）"
                } else {
                    "班次/时刻不全，请在 12306 / 航司 App 核实后手动添加"
                },
                isSuggestion = true,
            )
        }
        return MdTicket(inferTicketType(trainNo), trainNo, from, to, departure, seat, arrival)
    }

    /**
     * 解析「- 方式: 高铁 | 区间: 济南→泰安 | 建议时段: 第1天 上午 | 大约时长: 30分钟 | 大约票价: 二等座约30元」。
     * 车次与发车时间一律留空（0），由用户买到真票后自己填。
     * AI 不一定严格照键名写，所以没有键名但长得像区间的段也会被认出来。
     */
    private fun parseSuggestionLine(segs: List<String>): MdTicket? {
        var mode = ""
        var from = ""
        var to = ""
        val notes = mutableListOf<String>()

        for (seg in segs) {
            val k = keyOf(seg)
            val v = valueOf(seg)
            val bareRoute = if (k.isEmpty()) splitRoute(seg) else null
            when {
                k in SUGGESTION_KEYS -> if (mode.isBlank()) mode = v
                k in ROUTE_KEYS -> {
                    val route = splitRoute(v)
                    if (route != null && from.isBlank()) {
                        from = route.first
                        to = route.second
                    } else {
                        notes.add("$k: $v")
                    }
                }
                bareRoute != null && from.isBlank() -> {
                    from = bareRoute.first
                    to = bareRoute.second
                }
                else -> notes.add(if (k.isEmpty()) seg else "$k: $v")
            }
        }

        if (mode.isBlank() && from.isBlank() && to.isBlank() && notes.isEmpty()) return null
        return MdTicket(
            // 方式没写全时，退而在整行文字里找关键词
            type = inferTypeFromMode(mode.ifBlank { segs.joinToString(" ") }),
            trainNo = "",
            fromStation = from,
            toStation = to,
            departureTime = 0L,
            seat = "",
            arrivalTime = 0L,
            note = notes.joinToString(" · "),
            isSuggestion = true,
        )
    }

    /** 由「方式」文字猜交通类型，认不出来就当普通火车 */
    private fun inferTypeFromMode(mode: String): TicketType = when {
        mode.contains("高铁") || mode.contains("动车") -> TicketType.HIGH_SPEED_RAIL
        mode.contains("飞机") || mode.contains("航班") || mode.contains("机票") -> TicketType.FLIGHT
        mode.contains("大巴") || mode.contains("客车") || mode.contains("汽车") -> TicketType.BUS
        else -> TicketType.TRAIN
    }

    /** 把「济南→泰安」「济南-泰安」「济南至泰安」「济南到泰安」拆成两端；拆不出来返回 null */
    private fun splitRoute(s: String): Pair<String, String>? {
        val normalized = s.trim().replace("->", "→").replace("—", "→").replace("至", "→").replace("到", "→")
        val parts = normalized.split(Regex("[→\\-]")).map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.size >= 2) parts[0] to parts[1] else null
    }

    /** 取「键: 值」里的键；没有冒号返回空串（半角全角冒号都认） */
    private fun keyOf(seg: String): String {
        val i = seg.indexOfAny(charArrayOf(':', '：'))
        return if (i < 0) "" else seg.substring(0, i).trim()
    }

    /** 取「键: 值」里的值；没有冒号返回空串（半角全角冒号都认） */
    private fun valueOf(seg: String): String {
        val i = seg.indexOfAny(charArrayOf(':', '：'))
        return if (i < 0) "" else seg.substring(i + 1).trim()
    }

    /** 解析「2026-05-01 08:30」；也接受「2026/05/01」和只写时刻的「08:30」（用行程开始日期补全） */
    private fun parseDateTime(s: String, defaultDate: String = ""): Long = runCatching {
        var t = s.trim().replace("/", "-")
        if (Regex("""^\d{1,2}:\d{2}$""").matches(t) && defaultDate.isNotBlank()) {
            t = "${defaultDate}T$t"
        }
        LocalDateTime.parse(t.replace(" ", "T"))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    /** 车次/航班号推断交通类型：G/D/C=高铁；K/T/Z/Y/L 和纯数字=火车；「字母数字混合两位+3~4位数字」=航班 */
    private fun inferTicketType(no: String): TicketType {
        // 去掉连字符/空格再判（航班号常写成 MF-1020、HO 7356 这种）
        val s = no.trim().uppercase().replace("-", "").replace(" ", "")
        return when {
            Regex("""^[GDC]\d{1,4}$""").matches(s) -> TicketType.HIGH_SPEED_RAIL
            Regex("""^[KTZYL]\d{1,5}$""").matches(s) || Regex("""^\d{1,5}$""").matches(s) -> TicketType.TRAIN
            Regex("""^[0-9A-Z]{2}\d{3,4}$""").matches(s) && s.any { it.isLetter() } -> TicketType.FLIGHT
            else -> TicketType.TRAIN
        }
    }

    private fun parseDuration(s: String): Int? {
        val hour = Regex("""([\d.]+)\s*小时""").find(s)?.groupValues?.get(1)?.toDoubleOrNull()
        val min = Regex("""(\d+)\s*分钟""").find(s)?.groupValues?.get(1)?.toIntOrNull()
        val total = (hour?.times(60.0) ?: 0.0) + (min ?: 0)
        return if (total > 0) total.toInt() else null
    }
}

// ---- 导出 MD ----

object MdExporter {

    fun toMarkdown(
        trip: TripEntity,
        places: List<PlaceEntity>,
        plan: List<PlanItemEntity>,
        tickets: List<TicketEntity>,
    ): String {
        val sb = StringBuilder()
        sb.append("---\n旅行名称: ${trip.name}\n开始日期: ${trip.startDate}\n---\n\n")

        val placesById = places.associateBy { it.id }
        val planByDay = plan.groupBy { it.dayIndex }
        val dayCount = maxOf(trip.days, planByDay.keys.maxOrNull() ?: 0)
        // 顺序跟随枚举声明：上午/午餐/下午/晚餐/晚上
        val slotOrder = PlanSlot.entries.toList()

        for (day in 1..dayCount) {
            val items = planByDay[day].orEmpty()
            val city = items.mapNotNull { placesById[it.placeId]?.city }.firstOrNull { it.isNotBlank() }.orEmpty()
            sb.append("# 第${day}天")
            if (city.isNotBlank()) sb.append(" · $city")
            sb.append("\n")

            for (slot in slotOrder) {
                val slotItems = items.filter { it.slot == slot }.sortedBy { it.orderIndex }
                if (slotItems.isEmpty()) continue
                sb.append("## ${planSlotLabel(slot)}\n")
                for (si in slotItems) {
                    val p = placesById[si.placeId] ?: continue
                    sb.append(placeLine(p)).append("\n")
                }
            }

            if (day == 1) {
                val hotels = places.filter { it.type == PlaceType.HOTEL }
                if (hotels.isNotEmpty()) {
                    sb.append("## 酒店\n")
                    hotels.forEach { sb.append(placeLine(it)).append("\n") }
                }
            }
            sb.append("\n")
        }

        // 交通建议（AI 给的，无车次）与真实车票分开列，格式与解析端一致
        val suggestions = tickets.filter { it.isSuggestion }
        val realTickets = tickets.filter { !it.isSuggestion }
        if (suggestions.isNotEmpty()) {
            sb.append("# 交通建议\n")
            suggestions.forEach { sb.append(ticketLine(it)).append("\n") }
        }
        if (realTickets.isNotEmpty()) {
            sb.append("# 车票\n")
            realTickets.forEach { sb.append(ticketLine(it)).append("\n") }
        }
        return sb.toString()
    }

    private fun placeLine(p: PlaceEntity): String {
        val parts = mutableListOf("${placeTypeLabel(p.type)}: ${p.name}")
        if (p.address.isNotBlank()) parts.add("地址: ${p.address}")
        p.durationMinutes?.let { parts.add("时长: ${formatDuration(it)}") }
        if (p.needReservation) parts.add("预约: 是")
        p.reservationDate?.let { parts.add("预约日期: $it") }
        if (p.bookingInfo.isNotBlank()) parts.add("预约方式: ${p.bookingInfo}")
        p.pricePerPerson?.let { parts.add("人均: ${it}元") }
        p.pricePerNight?.let { parts.add("房价: ${it}元") }
        p.checkIn?.let { parts.add("入住: $it") }
        p.checkOut?.let { parts.add("退房: $it") }
        if (p.note.isNotBlank()) parts.add("备注: ${p.note}")
        return parts.joinToString(" | ")
    }

    private fun ticketLine(t: TicketEntity): String {
        if (t.isSuggestion) {
            val parts = mutableListOf("方式: ${ticketTypeText(t.type)}")
            if (t.fromStation.isNotBlank() || t.toStation.isNotBlank()) {
                parts.add("区间: ${t.fromStation}→${t.toStation}")
            }
            if (t.note.isNotBlank()) parts.add(t.note)
            return parts.joinToString(" | ")
        }
        val route = if (t.fromStation.isNotBlank() && t.toStation.isNotBlank()) "${t.fromStation}→${t.toStation}" else ""
        val arrival = t.arrivalTime?.takeIf { it > 0 }?.let { " | 到达: ${formatDateTime(it)}" }.orEmpty()
        return "车次: ${t.trainNo} | $route | ${formatDateTime(t.departureTime)} | ${t.seat}$arrival"
    }

    /** 把刚解析出的 MdDocument 重新渲染成规范 MD：清洗掉 AI 可能夹带的任何杂音，再用于展示和改稿 */
    fun fromDocument(doc: MdDocument): String {
        val sb = StringBuilder()
        sb.append("---\n旅行名称: ${doc.tripName}\n开始日期: ${doc.startDate}\n---\n\n")

        for (day in doc.days) {
            sb.append("# 第${day.dayIndex}天")
            if (day.city.isNotBlank()) sb.append(" · ${day.city}")
            sb.append("\n")

            val hotels = day.items.filter { it.slot == null }
            if (hotels.isNotEmpty()) {
                sb.append("## 酒店\n")
                hotels.forEach { sb.append(placeLine(it)).append("\n") }
            }
            val bySlot = day.items.filter { it.slot != null }.groupBy { it.slot!! }
            for (slot in PlanSlot.entries) {
                val items = bySlot[slot].orEmpty()
                if (items.isEmpty()) continue
                sb.append("## ${planSlotLabel(slot)}\n")
                items.forEach { sb.append(placeLine(it)).append("\n") }
            }
            sb.append("\n")
        }

        val suggestions = doc.tickets.filter { it.isSuggestion }
        val realTickets = doc.tickets.filter { !it.isSuggestion }
        if (suggestions.isNotEmpty()) {
            sb.append("# 交通建议\n")
            suggestions.forEach { sb.append(ticketLine(it)).append("\n") }
        }
        if (realTickets.isNotEmpty()) {
            sb.append("# 车票\n")
            realTickets.forEach { sb.append(ticketLine(it)).append("\n") }
        }
        return sb.toString().trimEnd()
    }

    private fun placeLine(p: MdPlace): String {
        val parts = mutableListOf("${placeTypeLabel(p.type)}: ${p.name}")
        if (p.address.isNotBlank()) parts.add("地址: ${p.address}")
        p.durationMinutes?.let { parts.add("时长: ${formatDuration(it)}") }
        if (p.needReservation) parts.add("预约: 是")
        p.reservationDate?.let { parts.add("预约日期: $it") }
        if (p.bookingInfo.isNotBlank()) parts.add("预约方式: ${p.bookingInfo}")
        p.pricePerPerson?.let { parts.add("人均: ${it}元") }
        p.pricePerNight?.let { parts.add("房价: ${it}元") }
        p.checkIn?.let { parts.add("入住: $it") }
        p.checkOut?.let { parts.add("退房: $it") }
        if (p.note.isNotBlank()) parts.add("备注: ${p.note}")
        return parts.joinToString(" | ")
    }

    private fun ticketLine(t: MdTicket): String {
        if (t.isSuggestion) {
            val parts = mutableListOf("方式: ${ticketTypeText(t.type)}")
            if (t.fromStation.isNotBlank() || t.toStation.isNotBlank()) {
                parts.add("区间: ${t.fromStation}→${t.toStation}")
            }
            if (t.note.isNotBlank()) parts.add(t.note)
            return parts.joinToString(" | ")
        }
        val route = if (t.fromStation.isNotBlank() && t.toStation.isNotBlank()) "${t.fromStation}→${t.toStation}" else ""
        val arrival = t.arrivalTime.takeIf { it > 0 }?.let { " | 到达: ${formatDateTime(it)}" }.orEmpty()
        return "车次: ${t.trainNo} | $route | ${formatDateTime(t.departureTime)} | ${t.seat}$arrival"
    }

    private fun ticketTypeText(t: TicketType): String = when (t) {
        TicketType.TRAIN -> "火车"
        TicketType.HIGH_SPEED_RAIL -> "高铁"
        TicketType.FLIGHT -> "航班"
        TicketType.BUS -> "大巴"
    }

    private fun formatDuration(min: Int): String = when {
        min % 60 == 0 -> "${min / 60}小时"
        min < 60 -> "${min}分钟"
        else -> "${min / 60}小时${min % 60}分钟"
    }

    private fun formatDateTime(epoch: Long): String =
        if (epoch == 0L) "" else Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}
