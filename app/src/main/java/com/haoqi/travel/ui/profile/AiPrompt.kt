package com.haoqi.travel.ui.profile

import com.haoqi.travel.data.local.entity.ProfileEntity

/** 生成页填写的旅行信息，用于拼 AI 提示词 */
data class PlanSpec(
    val startDate: String,
    val days: Int,
    val homeCity: String,
    val cities: List<String>, // 每天的主城市，按天顺序
    val note: String = "", // 用户的特殊要求备注
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

private fun hotelBudgetOf(profile: ProfileEntity?): String =
    profile?.hotelBudget.orEmpty().trim().ifBlank { "不限" }

private fun hotelTierOf(profile: ProfileEntity?): String =
    profile?.hotelTier.orEmpty().trim().ifBlank { "未填（预算内优先经济连锁酒店）" }

/** 用户希望住的位置（商圈/景点/学校等）；没填返回空串 = 自动找近地铁+市中心 */
private fun hotelAreaOf(profile: ProfileEntity?): String =
    profile?.hotelLocation.orEmpty().trim()

/**
 * 第一步「情报收集」提示词：联网只查真实车票 + 酒店，输出事实清单。
 * 查不到就写「未查到/待定」，绝不编造——这是避免幻觉的关键：让模型只报告事实、不做规划。
 */
fun buildSearchBrief(spec: PlanSpec, profile: ProfileEntity?): String {
    val cities = resolveCities(spec)
    val citySummary = cities.mapIndexed { i, c -> "第${i + 1}天:${c.ifBlank { "（沿用前一天）" }}" }
        .joinToString("，")
    val budget = hotelBudgetOf(profile)
    val transportClass = profile?.transportClass.orEmpty().ifBlank { "普通" }

    return """
你是旅行信息查询助手，会联网搜索。请联网查出下面旅行的**真实**交通班次和酒店。

**输出要求（极重要）**：你的回答**第一行就必须是「### 车票」**，前面不要任何说明、寒暄、思考过程；不要代码围栏；只输出下面两节清单，其余一律不写。

## 旅行信息
- 出发地：${spec.homeCity.trim().ifBlank { "未填" }}
- 日期：${spec.startDate}，共 ${spec.days} 天
- 每天城市：$citySummary
- 酒店每晚预算：$budget
- 酒店档位偏好：${hotelTierOf(profile)}
- 酒店位置要求：${hotelAreaOf(profile).ifBlank { "未指定（默认找近地铁、离市中心近的）" }}
- 出行档次：$transportClass
- 用户特殊要求：${spec.note.trim().ifBlank { "无" }}

## 请联网搜索并只输出下面两节

### 车票
- 去程、每一段城际换乘、返程各查一次。关键词用「城市A 到 城市B 高铁 时刻表」「城市A 到 城市B 高铁 车次 时间」（**不要带未来日期**：12306 只提前 15 天放票，高铁时刻表长期稳定，查稳定时刻即可）。
- 同一条线路**至少给出 2 趟真实候选车次**：优先包含一趟**全程最短的「快车」**，再补 1～2 趟其它时刻的车。每行都必须写全**全程时长**（规划时要按它选更快的车）：
  `- 车次: G1 | 北京南→南京南 | 发车 06:30 | 二等座 | 到达 11:24 | 约3小时24分钟`
- 查不到确切车次号/时刻的线路，写：`- 线路 北京→南京 未查到具体班次`

### 酒店
- **同一城市连续多天只住同一家**（只查一家，注明连住 N 晚）；只有换城市才查新的一家。
- **位置**：用户指定了位置（见上方「酒店位置要求」）就**只找那一带**（该商圈/景点/大学附近）的酒店，关键词用「位置 + 酒店 标准间」；没指定就默认找**近地铁、离市中心近、条件好（评分高）**的，**绝不要为了便宜推荐郊区/远郊的酒店**。
- **预算**：房价在预算内**尽量往上限靠**（如预算 200 元就找 160～200 元的市区连锁标准间，位置好值得把预算花足）；**预算 ≥100 元时不要推荐青旅/床位**（如 30 元青旅，远低于预算的不算符合预算），只有预算 <80 元或用户写明「越省越好」才考虑青旅。
- 档位参考：经济=汉庭/如家/7天/锦江之星/尚客优/格林豪泰；中端=全季/亚朵/维也纳；高端=四五星级。优先评分高、离地铁口近的。
- 写真实店名+地址+房价+是否营业：
  `- 酒店: 锦江之星(大明湖店) | 地址: xx区xx路 | 房价: 186元 | 营业中`
- 目标位置实在没有符合预算且营业中的，写：`- 酒店: 待定`

**禁止编造**：车次号、发车时刻、酒店名都必须来自搜索结果；查不到就写「未查到」或「待定」。
""".trimIndent()
}

/**
 * 第二步「规划」提示词。
 *
 * @param facts 第一步联网核实到的真实车票/酒店清单（Markdown）。为空 = 未联网。
 *   两步法把「搜索」和「规划」分离：规划模型只能引用 facts 里的车次/酒店，清单里没有的一律不写，
 *   从源头杜绝幻觉（车票不编车次、酒店不编店名）。
 */
fun buildPlanBrief(spec: PlanSpec, profile: ProfileEntity?, facts: String = ""): String {
    val cities = resolveCities(spec)
    val hasFacts = facts.isNotBlank()
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

    val rule7 = if (hasFacts) """
        7. 城际交通（去程、城际换乘、返程）：
           - **出发时刻（高铁基准）**：去程安排在旅行开始**前一天傍晚 18:00 前后**发车（车票日期=出发日前一天，第 1 天整天游玩）；返程安排在**最后一天傍晚 18:00 前后**发车（当天整天游玩）。若全程时长较长（约 4 小时以上），就**相应提前发车**，保证**到站后还能赶上当地地铁/公交**（到站目标不晚于 22:30，最迟不晚于当地末班地铁；宁可早到去市区吃晚饭，也不要深夜到站）。
           - **时长优先**：同一条线路「已核实信息」里有多趟真实车次时，**优先选全程时长最短的那趟**（如北京→南京有 3 小时多的 G 车就选它，不选 4 小时多的）；时长接近的再按上面的时刻基准选，一条线路最终只写一趟车。
           - 每条线路**只能从「已核实信息」清单里选车次/航班**，车次号、发车/到达时刻、全程时长**一字不差照抄清单**，禁止改动、禁止自创；清单里没给的线路 → 写「# 交通建议」（方式 + 大约时长）。
           - 舱位按「出行档次」：经济=绿皮/硬座；普通=二等座/经济舱；商务=一等座/商务座/头等舱。
           - 时间带完整日期（出发日前一天的车票日期也带完整日期），不要只写 8:30。
           - **绝不编造**：清单里没有的车次号/航班号/时刻一律不写。
    """ else """
        7. 城际交通单独列为「# 交通建议」：去程、城际换乘、返程各一条。
           **默认出发时刻（高铁基准）**：去程建议**旅行开始前一天傍晚 18:00 前后**出发（全程长就再提前，保证到站后还有末班地铁/公交，到站目标不晚于 22:30）；返程建议**最后一天傍晚 18:00 前后**出发（可提前）。
           **时长优先**：同一区间有多趟高铁时，选全程最短的（如北京→南京有 3 小时多的别选 4 小时多的）。
           **按「门到门总时间」比较飞机和高铁**：飞机加 提前 2 小时到机场 + 落地后约 1 小时进城（约 3 小时额外时间）；高铁站离市区近只加约 40 分钟。高铁全程 6 小时以内（约 1500 公里内，如北京—上海）优先高铁；超过 6 小时（如北京—广州）才优先飞机；短途或无机场才大巴。
           悠闲型建议时段不早于当天上午 9:00。舱位按「出行档次」。
           **严禁编造车次号、航班号以及任何具体发车/到达时刻**——你没有 12306 实时数据，编出来的是假信息。
           每条建议只给：方式、区间、建议时段（第几天 + 上午/下午/晚上）、大约时长、大约票价。
    """.trimIndent()

    val hotelRule = if (hasFacts) {
        "6. 每天推荐住宿（**同一城市连续多天住同一家，只列一次并标注入住/退房日期**；换城市才换酒店；不要写「续住」重复列）。酒店**只能从「已核实信息」清单里选、且店名一字不差照抄**；清单里是「待定」或没有的，就写「酒店: 待定（自行预订）」，绝不自编酒店名。"
    } else {
        "6. 每天写一行「酒店: 待定（建议住在 ${hotelAreaOf(profile).ifBlank { "近地铁/市中心" }}一带，预算内自行预订）」。本次未联网，**禁止写任何具体酒店名、更不许编造**。"
    }

    val factsBlock = if (hasFacts) """

## 【已核实信息（只能引用，禁止编造）】
$facts

（车票和酒店只能从上表里选：清单里没有的车次→写交通建议；清单里没有的酒店→写「待定」。）
""".trimIndent() else ""

    val ticketTemplate = if (hasFacts) """

        # 车票（从「已核实信息」清单里选真实车次；示例里的车次/时间只是格式占位）
        - 车次: 真实车次号 | 出发站→到达站 | 2026-05-01 08:30 | 二等座 | 到达: 2026-05-01 09:45
    """.trimIndent() else ""

    return """
你是专业的旅行规划助手。请为下面这次旅行规划详细行程，并**严格按指定的 Markdown 格式输出**，不要输出思考过程、任何解释、寒暄或代码围栏。

## 【我的个人偏好】
- 口味：辣度 ${profile?.spiceLevel.orEmpty().ifBlank { "不限" }}；忌口/过敏 ${profile?.avoidFood.orEmpty().ifBlank { "无" }}；偏爱菜系 ${profile?.cuisines.orEmpty().ifBlank { "不限" }}；每餐人均预算 ${profile?.mealBudget.orEmpty().ifBlank { "不限" }}
- 酒店：每晚预算 ${hotelBudgetOf(profile)}；档位 ${profile?.hotelTier.orEmpty().ifBlank { "不限" }}；位置偏好 ${profile?.hotelLocation.orEmpty().ifBlank { "不限" }}；早餐 ${if (profile?.hotelBreakfast == true) "需要" else "不限"}
- 出行：市内交通 ${profile?.transport.orEmpty().ifBlank { "不限" }}；体力 ${profile?.stamina.orEmpty().ifBlank { "不限" }}；同行 ${profile?.companion.orEmpty().ifBlank { "独自" }}；**出行档次 ${profile?.transportClass.orEmpty().ifBlank { "普通" }}**（经济=绿皮火车/硬座；普通=高铁二等座/飞机经济舱；商务=高铁一等座/商务座或飞机头等舱）

## 【旅行信息】
- 出发地：${spec.homeCity.trim().ifBlank { "未填写，请按目的地合理安排去程/返程，或自行合理假设" }}
- 日期：${spec.startDate}，共 ${spec.days} 天
- 每天主城市：$citySummary
- 节奏：$paceDesc

## 【用户的特殊要求（必须尽量满足）】
${spec.note.trim().ifBlank { "无" }}
$factsBlock
## 【规划要求】
（最重要）每天行程是主体：必须为每一天都完整给出「上午 / 午餐 / 下午 / 晚餐 / 晚上」的景点和饭店，以及当天酒店。城际交通/车票只是其中一小部分，**千万不要只输出交通或车票而漏掉每天的行程**。
0. **铁律（违背即失败）**：
   - **数量上限**：每天最多 3 个景点；「午餐」「晚餐」各**只 1 家**饭店；酒店**每天只 1 家**，连住只在第 1 天列一次（不写「续住」）。
   - **一行只写一个地点**：每行必须是 `- 景点: xxx`、`- 饭店: xxx` 或 `- 酒店: xxx`，绝不要把行程说明/串联路线/备选（如「趵突泉→大明湖」「00北京南出发…」）当成地点行。
   - **绝不重复**：同一个地点整个行程只出现一次，不要列多个备选。
1. 以「每天主城市」为准安排：当天住宿与主体在对应城市；如顺路且值得，可安排周边城市一日游，但一日游当天默认返回主城市住宿。
2. 按天分成「上午 / 午餐 / 下午 / 晚餐 / 晚上」，每天 $paceCount；同一天景点按地理位置就近安排，减少折返。
3. 换城市当天要安排好城际交通衔接。
4. 每个景点给出：地址、建议游玩时长（小时）、是否需要提前预约（`预约: 是/否`）、预约/购票方式（`预约方式: xxx`）。
5. 午餐和晚餐**都要**各推荐至少一家符合口味的饭店，标注大致人均；午餐放「## 午餐」，晚餐放「## 晚餐」**单独成段，不要挤占「## 晚上」**；「## 晚上」留给夜景、夜游、演出等，没有可省略。早餐一般简单解决，可不推荐。
$hotelRule
$rule7

## 【输出格式】严格使用下面的 Markdown 模板
---
旅行名称: ${autoTripName(cities, spec.days)}
开始日期: ${spec.startDate}
---

# 第1天 · 城市
## 酒店
- 酒店: 示例酒店 | 地址: 市中心xxx | 入住: 05-01 | 退房: 05-02 | 房价: 400元
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
    facts: String = "",
): String {
    return buildPlanBrief(spec, profile, facts) +
        "\n\n## 当前行程（请在此基础上修改）\n$currentMd" +
        "\n\n## 用户的新要求\n$instruction" +
        "\n\n**只修改用户要求的部分**：用户没提到的景点、饭店、酒店、车票、顺序、时间全部保持原样逐字不变；" +
        "不要重新规划、不要增删改任何用户没要求的内容。重新输出完整 Markdown（保持同样的模板与格式，不要输出思考过程、解释或代码围栏）。"
}
