package com.haoqi.travel.ui.profile

import com.haoqi.travel.data.local.entity.ProfileEntity

/** 生成页填写的旅行信息，用于拼 AI 提示词 */
data class PlanSpec(
    val startDate: String,
    val days: Int,
    val homeCity: String,
    val cities: List<String>, // 每天的主城市，按天顺序
)

/** 把每天的城市补齐：空白天沿用前一天的城市 */
fun resolveCities(spec: PlanSpec): List<String> {
    val out = spec.cities.map { it.trim() }.toMutableList()
    var prev = ""
    for (i in out.indices) {
        out[i] = if (out[i].isBlank()) prev else out[i]
        prev = out[i]
    }
    return out
}

/** 自动生成旅行名：如「济南·泰安 3日游」 */
fun autoTripName(resolvedCities: List<String>, days: Int): String {
    val cs = resolvedCities.filter { it.isNotBlank() }.distinct()
    return if (cs.isEmpty()) "我的旅行 ${days}日游" else "${cs.joinToString("·")} ${days}日游"
}

/**
 * 初始生成提示词。
 *
 * @param withTickets 是否允许输出具体车票：
 *   - false（联网搜索关）：只输出「# 交通建议」，严禁编造车次/时刻；
 *   - true（联网搜索开）：让模型先联网查真实班次，查到就输出「# 车票」，查不到退回「# 交通建议」。
 */
fun buildPlanBrief(spec: PlanSpec, profile: ProfileEntity?, withTickets: Boolean = false): String {
    val cities = resolveCities(spec)
    val citySummary = if (cities.all { it.isBlank() }) {
        "（未指定，请自行推荐一个热门目的地城市）"
    } else {
        cities.mapIndexed { i, c -> "第${i + 1}天:${c.ifBlank { "（沿用前一天）" }}" }.joinToString("，")
    }

    val pace = profile?.pace.orEmpty()
    val paceDesc = when (pace) {
        "紧凑型" -> "紧凑（每天 4–5 个景点，早起晚归、行程饱满）"
        "普通型" -> "普通（每天 3–4 个景点，节奏适中）"
        else -> "悠闲（睡到自然醒：上午最多 1 个轻松景点、也可以不安排，主力放下午，每天 1–2 个景点，不赶行程）"
    }
    val paceCount = when (pace) {
        "紧凑型" -> "4–5 个景点，早起晚归、行程饱满"
        "普通型" -> "3–4 个景点，节奏适中"
        else -> "1–2 个景点；上午起得晚，最多 1 个轻松景点或不安排，主力放下午，整体松弛不赶"
    }

    val rule7 = (if (withTickets) """
        7. 城际交通（去程、城际换乘、返程），按下面的要求逐项自查：
           a. **按「门到门总时间」比较飞机和高铁**：飞机要加 提前 2 小时到机场 + 落地后约 1 小时进市区（合计约 3 小时额外时间）；高铁站通常离市区近，只加约 40 分钟。**高铁全程 6 小时以内（约 1500 公里以内，如北京—上海）一律优先高铁**；超过 6 小时（如北京—广州）才优先飞机；短途或没有机场的地方才推荐大巴。
           b. **线路全覆盖，一条都不能少**：去程（出发地→第1天城市）、每一段城际换乘、返程（最后一天城市→出发地）都要给出，尤其不要漏掉返程。
           c. **每条线路只出现一次，且只给一个班次**：查到真实班次→写进「# 车票」，**只挑一个最合适的**（时间合适、直达优先），不要列多个备选让用户自己挑；没查到→写进「# 交通建议」；**绝不要同一条线路既给车票又给建议**。
           d. 联网搜索（web_search）：每条线路先搜一次，没查到可换关键词补搜一次，总共不超过 6 次，能查到就停；优先保证去程和返程。查完立即停止搜索，一次性输出完整行程 Markdown。搜索关键词参考：高铁/火车用「城市A到城市B 高铁 车次 日期」；机票用「出发城市 到达城市 航班 日期」（如「北京到广州 航班 2026-05-01」）。机票若两次都搜不到可靠的航班号和起降时刻，就把这条线路写成「方式: 飞机」的交通建议（注明大约飞行时长），**不要硬编航班号**。
           e. 「# 车票」严格按下面的格式，**时间必须带完整日期**（如 2026-05-01 08:30），不要只写 8:30：
              - 高铁/火车：- 车次: 真实车次号 | 出发站→到达站 | 2026-05-01 08:30 | 二等座 | 到达: 2026-05-01 09:45
              - 飞机：- 车次: 真实航班号 | 出发机场→到达机场 | 2026-05-01 08:30 | 经济舱 | 到达: 2026-05-01 11:25
           f. 「# 交通建议」只给：方式（高铁/火车/飞机/大巴）、区间、建议时段（第几天 + 上午/下午/晚上）、大约时长、大约票价。
           **绝不编造**：没查到的车次、航班号、发车/到达时间一律不要写；机票查不到真实航班号和起降时间就只给交通建议，宁可少给车票，也不能给假车票。
    """ else """
        7. 城际交通单独列为「# 交通建议」：包含去程（出发地→第1天城市）、城际换乘、返程（最后一天城市→出发地），一条都不能少，尤其不要漏掉返程。
           **按「门到门总时间」比较飞机和高铁**：飞机要加 提前 2 小时到机场 + 落地后约 1 小时进市区（合计约 3 小时额外时间）；高铁站通常离市区近，只加约 40 分钟。**高铁全程 6 小时以内（约 1500 公里以内，如北京—上海）一律优先高铁**；超过 6 小时（如北京—广州）才优先飞机；短途或没有机场的地方才推荐大巴。
           **严禁编造车次号、航班号以及任何具体发车/到达时刻**——你没有 12306 的实时数据，编出来的车次和时间是假信息，会误导用户，属于严重错误。
           每条交通建议只给：方式（高铁/火车/飞机/大巴）、区间、建议时段（第几天 + 上午/下午/晚上）、大约时长、大约票价。
           真实车票由用户自己去 12306 或航司 App 查询购买，你不需要（也不允许）替他确定具体班次。
    """).trimIndent()

    val ticketTemplate = if (withTickets) """

        # 车票（仅当你已用联网搜索查到真实班次时输出；示例里的车次/时间只是格式占位，勿照抄）
        - 车次: 真实车次号 | 出发站→到达站 | 2026-05-01 08:30 | 二等座 | 到达: 2026-05-01 09:45
    """.trimIndent() else ""

    return """
你是专业的旅行规划助手。请为下面这次旅行规划详细行程，并**严格按指定的 Markdown 格式输出**，不要输出思考过程、任何解释、寒暄或代码围栏。

## 【我的个人偏好】
- 口味：辣度 ${profile?.spiceLevel.orEmpty().ifBlank { "不限" }}；忌口/过敏 ${profile?.avoidFood.orEmpty().ifBlank { "无" }}；偏爱菜系 ${profile?.cuisines.orEmpty().ifBlank { "不限" }}；每餐人均预算 ${profile?.mealBudget.orEmpty().ifBlank { "不限" }}
- 酒店：每晚预算 ${profile?.hotelBudget.orEmpty().ifBlank { "不限" }}；档位 ${profile?.hotelTier.orEmpty().ifBlank { "不限" }}；位置偏好 ${profile?.hotelLocation.orEmpty().ifBlank { "不限" }}；早餐 ${if (profile?.hotelBreakfast == true) "需要" else "不限"}
- 出行：市内交通 ${profile?.transport.orEmpty().ifBlank { "不限" }}；体力 ${profile?.stamina.orEmpty().ifBlank { "不限" }}；同行 ${profile?.companion.orEmpty().ifBlank { "独自" }}

## 【旅行信息】
- 出发地：${spec.homeCity.trim().ifBlank { "未填写，请按目的地合理安排去程/返程，或自行合理假设" }}
- 日期：${spec.startDate}，共 ${spec.days} 天
- 每天主城市：$citySummary
- 节奏：$paceDesc

## 【规划要求】
（最重要）每天行程是主体：必须为每一天都完整给出「上午 / 午餐 / 下午 / 晚餐 / 晚上」的景点和饭店，以及当天酒店。城际交通/车票只是其中一小部分，**千万不要只输出交通或车票而漏掉每天的行程**。
1. 以「每天主城市」为准安排：当天住宿与主体在对应城市；如顺路且值得，可安排周边城市一日游（如济南→泰山），但一日游当天默认返回主城市住宿，除非特别说明。
2. 按天分成「上午 / 午餐 / 下午 / 晚餐 / 晚上」，每天 $paceCount；同一天景点按地理位置就近安排，减少折返。
3. 换城市当天要安排好城际交通衔接。
4. 每个景点给出：地址、建议游玩时长（小时）、是否需要提前预约（`预约: 是/否`）、以及预约/购票方式（`预约方式: xxx`，如「官方小程序提前7天预约」「官网购票」「现场购票」）。若已开启联网搜索，请用 web_search 查景点官方预约/购票渠道，写进 `预约方式`；不要凭空编造渠道。
5. 午餐和晚餐**都要**各推荐至少一家符合口味的饭店，标注大致人均；午餐放「## 午餐」，晚餐放「## 晚餐」**单独成段，不要挤占「## 晚上」**；「## 晚上」留给夜景、夜游、演出等行程，没有可省略。早餐一般简单解决，可不推荐、不占行程。
6. 每天（尤其是换城市或换酒店的日子）推荐符合预算档位的酒店，标注入住/退房日期。
$rule7

## 【输出格式】严格使用下面的 Markdown 模板
---
旅行名称: ${autoTripName(cities, spec.days)}
开始日期: ${spec.startDate}
---

# 第1天 · 城市
## 酒店
- 酒店: 全季酒店(示例店) | 地址: 市中心xxx | 入住: 05-01 | 退房: 05-02 | 房价: 400元
## 上午
- 景点: 示例景点 | 地址: 城市xxx | 时长: 2小时 | 预约: 否
## 午餐
- 饭店: 示例饭店 | 地址: 城市xxx | 人均: 60元
## 下午
- 景点: 示例景点 | 地址: 城市xxx | 时长: 3小时 | 预约: 是 | 预约日期: 05-01 | 预约方式: 官方小程序提前7天预约
## 晚餐
- 饭店: 示例饭店 | 地址: 城市xxx | 人均: 80元
## 晚上
- 景点: 示例景点(夜景，没有可省略) | 地址: 城市xxx

# 交通建议
- 方式: 高铁 | 区间: 出发城市→目的城市 | 建议时段: 第1天 上午 | 大约时长: 1小时30分钟 | 大约票价: 二等座约120元
- 方式: 高铁 | 区间: 目的城市→出发城市 | 建议时段: 第3天 晚上 | 大约时长: 1小时30分钟 | 大约票价: 二等座约120元
$ticketTemplate

现在，请开始规划。
""".trimIndent()
}

/** 改稿提示词：在已有行程基础上按新要求修改并重出完整 MD */
fun buildRefinePrompt(
    spec: PlanSpec,
    profile: ProfileEntity?,
    currentMd: String,
    instruction: String,
    withTickets: Boolean = false,
): String {
    return buildPlanBrief(spec, profile, withTickets) +
        "\n\n## 当前行程（请在此基础上修改）\n$currentMd" +
        "\n\n## 用户的新要求\n$instruction" +
        "\n\n请严格按新要求修改上面的行程，并重新输出完整 Markdown（保持同样的模板与格式，不要输出思考过程、解释或代码围栏）。"
}
