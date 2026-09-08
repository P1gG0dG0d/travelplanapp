package com.haoqi.travel.data.importer

import com.haoqi.travel.data.local.entity.PlaceType
import com.haoqi.travel.data.local.entity.PlanSlot
import com.haoqi.travel.data.local.entity.TicketType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 MdParser 能正确解析「AI 生成行程」提示词模板要求输出的 Markdown。
 * 这份样例就是 buildAiPrompt 模板 + DeepSeek/GLM 按模板作答后的典型样子。
 */
class MdParserTest {

    private val sample = """
---
旅行名称: 杭州三日游
开始日期: 2026-05-01
---

# 第1天 · 杭州
## 酒店
- 酒店: 全季酒店(西湖店) | 地址: 杭州市上城区xx路 | 入住: 05-01 | 退房: 05-02 | 房价: 400元

## 上午
- 景点: 西湖景区 | 地址: 杭州市西湖区 | 时长: 3小时 | 预约: 否

## 午餐
- 饭店: 外婆家 | 地址: 杭州市西湖区 | 人均: 80元

## 下午
- 景点: 灵隐寺 | 地址: 杭州市西湖区 | 时长: 2小时 | 预约: 是 | 预约日期: 05-01

# 第2天 · 杭州
## 上午
- 景点: 西溪湿地 | 地址: 杭州市余杭区 | 时长: 4小时 | 预约: 否

## 下午
- 景点: 河坊街 | 地址: 杭州市上城区 | 时长: 2小时

# 车票
- 车次: G1234 | 杭州东→北京南 | 2026-05-03 14:00 | 二等座
""".trimIndent()

    @Test
    fun `parses realistic AI markdown`() {
        val doc = MdParser.parse(sample)

        assertEquals("杭州三日游", doc.tripName)
        assertEquals("2026-05-01", doc.startDate)
        assertEquals(2, doc.days.size)

        val day1 = doc.days.first { it.dayIndex == 1 }
        assertEquals("杭州", day1.city)
        assertEquals(4, day1.items.size)

        val hotel = day1.items.first { it.type == PlaceType.HOTEL }
        assertNull(hotel.slot)
        assertEquals("全季酒店(西湖店)", hotel.name)
        assertEquals("400", hotel.pricePerNight.toString())

        val xihu = day1.items.first { it.name == "西湖景区" }
        assertEquals(PlaceType.ATTRACTION, xihu.type)
        assertEquals(PlanSlot.MORNING, xihu.slot)
        assertEquals(180, xihu.durationMinutes)
        assertEquals(false, xihu.needReservation)

        val lunch = day1.items.first { it.name == "外婆家" }
        assertEquals(PlaceType.RESTAURANT, lunch.type)
        assertEquals(PlanSlot.LUNCH, lunch.slot)
        assertEquals(80, lunch.pricePerPerson)

        val lingyin = day1.items.first { it.name == "灵隐寺" }
        assertEquals(PlanSlot.AFTERNOON, lingyin.slot)
        assertTrue(lingyin.needReservation)
        assertEquals("05-01", lingyin.reservationDate)

        val day2 = doc.days.first { it.dayIndex == 2 }
        assertEquals(2, day2.items.size)
        assertEquals(PlanSlot.MORNING, day2.items.first().slot)

        assertEquals(1, doc.tickets.size)
        val ticket = doc.tickets.first()
        assertEquals("G1234", ticket.trainNo)
        assertEquals(TicketType.HIGH_SPEED_RAIL, ticket.type)
        assertTrue(ticket.departureTime > 0)
    }

    @Test
    fun `parses code-fenced markdown`() {
        val fenced = "```markdown\n$sample\n```"
        val cleaned = fenced.trim()
            .removePrefix("```markdown")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val doc = MdParser.parse(cleaned)
        assertEquals(2, doc.days.size)
        assertEquals(1, doc.tickets.size)
    }

    /** AI 现在只给「交通建议」，不允许编造车次和发车时间 */
    private val suggestionSample = """
---
旅行名称: 济南泰安三日游
开始日期: 2026-05-01
---

# 第1天 · 泰安
## 上午
- 景点: 泰山 | 地址: 泰安市泰山区 | 时长: 5小时 | 预约: 是 | 预约日期: 05-01

# 交通建议
- 方式: 高铁 | 区间: 济南→泰安 | 建议时段: 第1天 上午 | 大约时长: 30分钟 | 大约票价: 二等座约30元
- 方式: 飞机 | 区间: 泰安 → 上海 | 建议时段: 第3天 晚上 | 大约时长: 1小时40分钟
""".trimIndent()

    @Test
    fun `traffic suggestions carry no fabricated train number or time`() {
        val doc = MdParser.parse(suggestionSample)
        assertEquals(1, doc.days.size)
        assertEquals(2, doc.tickets.size)

        val go = doc.tickets[0]
        assertTrue("交通建议必须标记为 isSuggestion", go.isSuggestion)
        assertEquals("交通建议不允许带车次", "", go.trainNo)
        assertEquals("交通建议不允许带发车时间", 0L, go.departureTime)
        assertEquals(TicketType.HIGH_SPEED_RAIL, go.type)
        assertEquals("济南", go.fromStation)
        assertEquals("泰安", go.toStation)
        assertTrue(go.note.contains("建议时段: 第1天 上午"))
        assertTrue(go.note.contains("大约票价: 二等座约30元"))

        val back = doc.tickets[1]
        assertEquals(TicketType.FLIGHT, back.type)
        assertEquals("泰安", back.fromStation)
        assertEquals("上海", back.toStation)
    }

    @Test
    fun `suggestion lines survive sloppy AI formatting`() {
        val sloppy = """
---
旅行名称: 测试
开始日期: 2026-05-01
---

# 第1天 · 北京
## 上午
- 景点: 故宫 | 地址: 北京市东城区 | 时长: 3小时

# 交通
- 方式：大巴 | 区间: 天津至北京 | 大约时长: 2小时
- 高铁 | 北京→济南 | 第3天 晚上 | 约1小时30分钟
""".trimIndent()
        val doc = MdParser.parse(sloppy)
        assertEquals(2, doc.tickets.size)

        // 全角冒号 + 「至」写法
        val bus = doc.tickets[0]
        assertTrue(bus.isSuggestion)
        assertEquals(TicketType.BUS, bus.type)
        assertEquals("天津", bus.fromStation)
        assertEquals("北京", bus.toStation)

        // 完全没有键名，也要能从「# 交通」段落认出区间和方式
        val train = doc.tickets[1]
        assertTrue(train.isSuggestion)
        assertEquals(TicketType.HIGH_SPEED_RAIL, train.type)
        assertEquals("北京", train.fromStation)
        assertEquals("济南", train.toStation)
    }

    @Test
    fun `explicit train number under suggestion header is still a real ticket`() {
        val md = """
---
旅行名称: 测试
开始日期: 2026-05-01
---

# 第1天 · 北京
## 上午
- 景点: 故宫 | 地址: 北京市东城区 | 时长: 3小时

# 交通建议
- 车次: G1234 | 济南→北京 | 2026-05-01 08:00 | 二等座
""".trimIndent()
        val doc = MdParser.parse(md)
        val t = doc.tickets.single()
        assertEquals(false, t.isSuggestion)
        assertEquals("G1234", t.trainNo)
        assertTrue(t.departureTime > 0)
    }

    @Test
    fun `parses reservation and booking method`() {
        val md = """
---
旅行名称: 测试
开始日期: 2026-05-01
---

# 第1天 · 北京
## 上午
- 景点: 故宫 | 地址: 北京市东城区 | 时长: 3小时 | 预约: 是 | 预约方式: 微信小程序「故宫博物院」提前7天预约
## 下午
- 景点: 什刹海 | 地址: 北京市西城区 | 预约: 否 | 预约方式: 现场购票
""".trimIndent()
        val doc = MdParser.parse(md)

        val gugong = doc.days[0].items.first { it.name == "故宫" }
        assertTrue(gugong.needReservation)
        assertEquals("微信小程序「故宫博物院」提前7天预约", gugong.bookingInfo)

        val shichahai = doc.days[0].items.first { it.name == "什刹海" }
        assertEquals(false, shichahai.needReservation)
        assertEquals("现场购票", shichahai.bookingInfo)
    }

    @Test
    fun `dinner parses into its own slot separate from evening`() {
        val md = """
---
旅行名称: 测试
开始日期: 2026-05-01
---

# 第1天 · 北京
## 下午
- 景点: 故宫 | 地址: 北京市东城区 | 时长: 3小时
## 晚餐
- 饭店: 烤鸭店 | 地址: 北京市东城区 | 人均: 120元
## 晚上
- 景点: 什刹海夜景 | 地址: 北京市西城区
""".trimIndent()
        val doc = MdParser.parse(md)

        val dinner = doc.days[0].items.first { it.name == "烤鸭店" }
        assertEquals(PlanSlot.DINNER, dinner.slot)
        assertEquals(PlaceType.RESTAURANT, dinner.type)

        val night = doc.days[0].items.first { it.name == "什刹海夜景" }
        assertEquals(PlanSlot.EVENING, night.slot)
        assertEquals(PlaceType.ATTRACTION, night.type)
    }

    @Test
    fun `suggestion dropped when a real ticket covers the same route`() {
        val md = """
---
旅行名称: 测试
开始日期: 2026-05-01
---

# 第1天 · 北京
## 上午
- 景点: 故宫 | 地址: 北京市东城区

# 交通建议
- 方式: 高铁 | 区间: 济南→北京 | 建议时段: 第1天 上午 | 大约时长: 2小时
- 方式: 高铁 | 区间: 北京→济南 | 建议时段: 第3天 晚上 | 大约时长: 2小时

# 车票
- 车次: G1234 | 济南东→北京南 | 2026-05-01 08:30 | 二等座 | 到达: 2026-05-01 10:30
""".trimIndent()
        val doc = MdParser.parse(md)

        // 去程建议被真实车票顶掉，只剩返程建议 + 1 张真实车票
        assertEquals(2, doc.tickets.size)
        val real = doc.tickets.single { !it.isSuggestion }
        assertEquals("G1234", real.trainNo)
        val kept = doc.tickets.filter { it.isSuggestion }
        assertEquals(1, kept.size)
        assertEquals("北京", kept[0].fromStation)
        assertEquals("济南", kept[0].toStation)
    }

    @Test
    fun `flight ticket parses flight number and arrival`() {
        val md = """
---
旅行名称: 测试
开始日期: 2026-05-01
---

# 第1天 · 广州
## 上午
- 景点: 广州塔 | 地址: 广州市海珠区

# 车票
- 车次: CA1831 | 北京首都→广州白云 | 2026-05-01 08:30 | 经济舱 | 到达: 2026-05-01 11:25
""".trimIndent()
        val t = MdParser.parse(md).tickets.single()

        assertEquals(false, t.isSuggestion)
        assertEquals("CA1831", t.trainNo)
        assertEquals(TicketType.FLIGHT, t.type)
        assertEquals("北京首都", t.fromStation)
        assertEquals("广州白云", t.toStation)
        assertTrue(t.departureTime > 0)
        assertTrue(t.arrivalTime > 0)
    }

    @Test
    fun `time-only ticket times use trip start date`() {
        val md = """
---
旅行名称: 测试
开始日期: 2026-05-01
---

# 第1天 · 泰安
## 上午
- 景点: 泰山 | 地址: 泰安市

# 车票
- 车次: G123 | 济南→泰安 | 08:30 | 二等座 | 到达: 09:00
""".trimIndent()
        val t = MdParser.parse(md).tickets.single()

        assertTrue(t.departureTime > 0)
        assertTrue(t.arrivalTime > 0)
        val dep = java.time.Instant.ofEpochMilli(t.departureTime).atZone(java.time.ZoneId.systemDefault())
        assertEquals(8, dep.hour)
        assertEquals(1, dep.dayOfMonth)
    }

    @Test
    fun `train number without departure time downgrades to suggestion`() {
        // 只有班次号、没有可解析的发车时间 → 是笼统建议，不能标成车票
        val md = """
---
旅行名称: 测试
开始日期: 2026-05-01
---

# 第1天 · 泰安
## 上午
- 景点: 泰山 | 地址: 泰安市

# 车票
- 车次: G123 | 济南→泰安 | 二等座
- 车次: G456 | 济南→泰安 | 建议时段: 第1天 上午
""".trimIndent()
        val tickets = MdParser.parse(md).tickets

        assertEquals(2, tickets.size)
        assertTrue("没发车时间的必须降级为建议", tickets.all { it.isSuggestion })
        assertTrue("降级后不能带车次号", tickets.all { it.trainNo.isEmpty() })
        assertTrue(tickets[0].note.contains("参考班次: G123"))
        assertEquals("济南", tickets[0].fromStation)
        assertEquals("泰安", tickets[0].toStation)
    }

    @Test
    fun `duplicate real tickets on same route keep the earliest`() {
        // AI 列了两个回程航班让用户挑：只保留出发时间最早的那个
        val md = """
---
旅行名称: 测试
开始日期: 2026-05-01
---

# 第1天 · 广州
## 上午
- 景点: 广州塔 | 地址: 广州市海珠区

# 车票
- 车次: CZ3102 | 广州白云→北京首都 | 2026-05-03 15:00 | 经济舱 | 到达: 2026-05-03 18:00
- 车次: CZ3101 | 广州白云→北京首都 | 2026-05-03 08:30 | 经济舱 | 到达: 2026-05-03 11:30
""".trimIndent()
        val tickets = MdParser.parse(md).tickets

        assertEquals(1, tickets.size)
        assertEquals("CZ3101", tickets[0].trainNo)
        assertTrue(tickets[0].departureTime > 0)
    }

    @Test
    fun `route separated by dao character still parses`() {
        // 「北京到广州」这种写法以前拆不出区间，导致建议和车票无法去重
        val md = """
---
旅行名称: 测试
开始日期: 2026-05-01
---

# 第1天 · 广州
## 上午
- 景点: 广州塔 | 地址: 广州市海珠区

# 交通建议
- 方式: 飞机 | 区间: 北京到广州 | 建议时段: 第1天 上午

# 车票
- 车次: CA1831 | 北京首都→广州白云 | 2026-05-01 08:30 | 经济舱 | 到达: 2026-05-01 11:25
""".trimIndent()
        val tickets = MdParser.parse(md).tickets

        // 建议和车票是同一条线路（北京→广州），只保留具体航班
        assertEquals(1, tickets.size)
        assertEquals(false, tickets[0].isSuggestion)
        assertEquals("CA1831", tickets[0].trainNo)
    }

    @Test
    fun `flight number with hyphen infers flight`() {
        val md = """
---
旅行名称: 测试
开始日期: 2026-05-01
---

# 第1天 · 北京
## 上午
- 景点: 故宫 | 地址: 北京市东城区

# 车票
- 车次: MF-1020 | 广州白云→北京大兴 | 2026-05-03 10:00 | 经济舱 | 到达: 2026-05-03 13:00
""".trimIndent()
        val t = MdParser.parse(md).tickets.single()
        assertEquals(TicketType.FLIGHT, t.type)
        assertEquals("MF-1020", t.trainNo)
    }

    @Test
    fun `duplicate suggestions and self loops are dropped`() {
        val md = """
---
旅行名称: 测试
开始日期: 2026-05-01
---

# 第1天 · 武汉
## 上午
- 景点: 黄鹤楼 | 地址: 武汉市

# 交通建议
- 方式: 高铁 | 区间: 洛阳→西安 | 建议时段: 第3天 上午 | 大约时长: 1小时30分钟
- 方式: 高铁 | 区间: 洛阳→西安 | 建议时段: 第3天 上午 | 大约时长: 1小时30分钟
- 方式: 高铁 | 区间: 武汉→武汉 | 建议时段: 第2天 晚上
""".trimIndent()
        val tickets = MdParser.parse(md).tickets

        // 两条重复的「洛阳→西安」只留一条；「武汉→武汉」自环直接丢弃
        assertEquals(1, tickets.size)
        assertEquals("洛阳", tickets[0].fromStation)
        assertEquals("西安", tickets[0].toStation)
    }
}
