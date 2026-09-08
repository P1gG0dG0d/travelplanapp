package com.haoqi.travel.ui.profile

import com.haoqi.travel.data.local.entity.ProfileEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁死提示词里最容易被改坏的两条规则：
 *  1. 不许让 AI 编造车次和发车时间（用户明确反馈过「车票全是假的」）
 *  2. 节奏偏好要真的写进提示词
 */
class AiPromptTest {

    private val prompt = buildPlanBrief(
        PlanSpec("2026-05-01", 3, "济南", listOf("泰安", "泰安", "济南")),
        null,
    )

    @Test
    fun `prompt asks for traffic suggestions and forbids fabricated train numbers`() {
        assertTrue("必须要求输出「# 交通建议」段落", prompt.contains("# 交通建议"))
        assertTrue("必须明确禁止编造车次", prompt.contains("严禁编造车次号"))
        assertFalse("模板里不该再出现具体车次示例", prompt.contains("车次: G1234"))
        assertFalse("不该再要求「# 车票」段落", prompt.contains("# 车票"))
    }

    @Test
    fun `refine prompt keeps the same rules`() {
        val refined = buildRefinePrompt(
            PlanSpec("2026-05-01", 3, "济南", listOf("泰安", "泰安", "济南")),
            null,
            "当前行程原文",
            "第二天改成去曲阜",
        )
        assertTrue(refined.contains("# 交通建议"))
        assertTrue(refined.contains("严禁编造车次号"))
        assertTrue(refined.contains("第二天改成去曲阜"))
    }

    @Test
    fun `pace preference reaches the prompt`() {
        val compact = buildPlanBrief(
            PlanSpec("2026-05-01", 2, "济南", listOf("泰安", "泰安")),
            ProfileEntity(pace = "紧凑型"),
        )
        assertTrue(compact.contains("4–5 个景点"))

        val relaxed = buildPlanBrief(
            PlanSpec("2026-05-01", 2, "济南", listOf("泰安", "泰安")),
            ProfileEntity(pace = "悠闲型"),
        )
        assertTrue(relaxed.contains("1–2 个景点"))
        assertTrue("悠闲型要体现上午起得晚", relaxed.contains("上午最多 1 个轻松景点"))
    }

    @Test
    fun `web search mode asks for real tickets but still forbids making them up`() {
        val withTickets = buildPlanBrief(
            PlanSpec("2026-05-01", 3, "济南", listOf("泰安", "泰安", "济南")),
            null,
            withTickets = true,
        )
        assertTrue("联网模式下要允许输出「# 车票」段落", withTickets.contains("# 车票"))
        assertTrue("要提示用 web_search 查真实班次", withTickets.contains("web_search"))
        assertTrue("查不到时仍要退回「交通建议」", withTickets.contains("绝不编造"))
    }

    @Test
    fun `refine prompt carries the ticket mode too`() {
        val refined = buildRefinePrompt(
            PlanSpec("2026-05-01", 3, "济南", listOf("泰安", "泰安", "济南")),
            null,
            "当前行程原文",
            "改改交通",
            withTickets = true,
        )
        assertTrue(refined.contains("# 车票"))
        assertTrue(refined.contains("web_search"))
    }

    @Test
    fun `prompt picks transport by distance and requires dinner and booking method`() {
        assertTrue("长途才优先飞机", prompt.contains("才优先飞机"))
        assertTrue("要按门到门时间比较飞机和高铁", prompt.contains("门到门"))
        assertTrue("北京到上海这类距离要优先高铁", prompt.contains("北京—上海）一律优先高铁"))
        assertTrue("午餐和晚餐都要推荐", prompt.contains("午餐和晚餐"))
        assertTrue("早餐可不推荐", prompt.contains("早餐一般简单解决"))
        assertTrue("要标注预约/购票方式", prompt.contains("预约方式"))
    }

    @Test
    fun `prompt has separate dinner slot and per-route ticket rules`() {
        assertTrue("模板要有独立的晚餐段", prompt.contains("## 晚餐"))
        assertTrue("晚餐不挤占晚上", prompt.contains("不要挤占「## 晚上」"))

        val withTickets = buildPlanBrief(
            PlanSpec("2026-05-01", 3, "北京", listOf("广州", "广州", "北京")),
            null,
            withTickets = true,
        )
        assertTrue("线路要全覆盖", withTickets.contains("一条都不能少"))
        assertTrue("同一线路不能既给车票又给建议", withTickets.contains("既给车票又给建议"))
        assertTrue("每条线路只给一个最佳班次", withTickets.contains("只挑一个最合适"))
        assertTrue("时间必须带完整日期", withTickets.contains("不要只写 8:30"))
    }
}
