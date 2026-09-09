package com.haoqi.travel.ui.profile

import com.haoqi.travel.data.local.entity.ProfileEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁死两步法提示词的核心规则：
 *  1. 不联网时只给交通建议、酒店写待定，绝不编造车次/酒店名；
 *  2. 联网时规划模型只能引用「已核实信息」清单；
 *  3. 交通按距离选、晚餐单独成段、节奏写进提示词。
 */
class AiPromptTest {

    private val prompt = buildPlanBrief(
        PlanSpec("2026-05-01", 3, "济南", listOf("泰安", "泰安", "济南")),
        null,
    )

    @Test
    fun `不联网时只给交通建议、禁止编造车次`() {
        assertTrue(prompt.contains("# 交通建议"))
        assertTrue(prompt.contains("严禁编造车次号"))
        assertFalse(prompt.contains("车次: G1234"))
        assertFalse(prompt.contains("# 车票"))
    }

    @Test
    fun `不联网时酒店写待定、禁止写具体酒店名`() {
        assertTrue(prompt.contains("酒店: 待定"))
        assertTrue(prompt.contains("禁止写任何具体酒店名"))
    }

    @Test
    fun `联网时引用已核实清单、只从清单选车次`() {
        val facts = "### 车票\n- 车次: G1 | 北京南→南京南 | 发车 06:30\n### 酒店\n- 酒店: 锦江之星 | 房价: 149元 | 营业中"
        val p = buildPlanBrief(
            PlanSpec("2026-05-01", 3, "北京", listOf("南京", "南京", "北京")),
            null,
            facts,
        )
        assertTrue(p.contains("# 车票"))
        assertTrue(p.contains("已核实信息"))
        assertTrue(p.contains("只能从「已核实信息」清单里选"))
        assertTrue(p.contains("G1"))
    }

    @Test
    fun `refine 复用 facts 并保持原样`() {
        val refined = buildRefinePrompt(
            PlanSpec("2026-05-01", 3, "济南", listOf("泰安", "泰安", "济南")),
            null,
            "当前行程原文",
            "第二天改成去曲阜",
        )
        assertTrue(refined.contains("# 交通建议"))
        assertTrue(refined.contains("严禁编造车次号"))
        assertTrue(refined.contains("第二天改成去曲阜"))
        assertTrue(refined.contains("只修改用户要求的部分"))
    }

    @Test
    fun `pace 写进提示词`() {
        val compact = buildPlanBrief(PlanSpec("2026-05-01", 2, "济南", listOf("泰安", "泰安")), ProfileEntity(pace = "紧凑型"))
        assertTrue(compact.contains("4–5 个景点"))
        val relaxed = buildPlanBrief(PlanSpec("2026-05-01", 2, "济南", listOf("泰安", "泰安")), ProfileEntity(pace = "悠闲型"))
        assertTrue(relaxed.contains("1–2 个景点"))
    }

    @Test
    fun `交通按距离选、晚餐单独成段、预约方式`() {
        assertTrue(prompt.contains("才优先飞机"))
        assertTrue(prompt.contains("门到门"))
        assertTrue(prompt.contains("北京—上海）优先高铁"))
        assertTrue(prompt.contains("## 晚餐"))
        assertTrue(prompt.contains("不要挤占「## 晚上」"))
        assertTrue(prompt.contains("预约方式"))
    }

    @Test
    fun `搜索简报要求联网查真实车票酒店、禁止编造`() {
        val brief = buildSearchBrief(PlanSpec("2026-05-01", 3, "北京", listOf("南京", "南京", "北京")), null)
        assertTrue(brief.contains("车票"))
        assertTrue(brief.contains("酒店"))
        assertTrue(brief.contains("禁止编造"))
        assertTrue(brief.contains("时刻表"))
    }
}
