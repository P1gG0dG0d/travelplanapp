package com.haoqi.travel.data.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * 锁死「从 AI 返回文本里抽行程正文」的清洗逻辑：
 * DeepSeek 会把思考过程写在正文前面、或把正文包进代码围栏，直接导入会导致行程乱掉。
 * 联网搜索（Responses API）时还会在正答前输出一堆「搜索进度」说明，必须只取最后一条。
 */
class AiClientTest {

    private val md = """
        ---
        旅行名称: 测试
        开始日期: 2026-05-01
        ---

        # 第1天 · 济南
        ## 上午
        - 景点: 大明湖
    """.trimIndent()

    @Test
    fun `reasoning text before markdown is cut away`() {
        val raw = "让我想想……先查一下天气，再安排行程。\n\n$md"
        val result = extractItineraryMarkdown(raw)
        assertFalse("思考文字不能混进正文", result.contains("让我想想"))
        assertTrue("正文要从第1天开始", result.startsWith("# 第1天"))
        assertTrue(result.contains("大明湖"))
    }

    @Test
    fun `code fenced markdown is unwrapped even with prose outside`() {
        val raw = "好的，以下是行程：\n```markdown\n$md\n```\n希望你喜欢"
        val result = extractItineraryMarkdown(raw)
        assertTrue(result.startsWith("# 第1天"))
        assertTrue(result.contains("大明湖"))
        assertFalse("围栏标记要被去掉", result.contains("```"))
        assertFalse("围栏外的寒暄要去掉", result.contains("希望你喜欢"))
    }

    @Test
    fun `markdown without yaml is cut from the first day header`() {
        val raw = "思考中……\n\n# 第1天 · 济南\n## 上午\n- 景点: 大明湖"
        val result = extractItineraryMarkdown(raw)
        assertTrue(result.startsWith("# 第1天"))
        assertFalse(result.contains("思考中"))
        assertTrue(result.contains("大明湖"))
    }

    @Test
    fun `tickets section does not swallow day content before it`() {
        // 模型漏写 YAML 头和 # 第N天 时，不能因为后面出现「# 交通建议 / # 车票」就把前面的每日行程切掉
        val raw = """
            以下是行程：

            ## 上午
            - 景点: 大明湖

            ## 下午
            - 景点: 趵突泉

            # 交通建议
            - 方式: 高铁 | 济南→泰安 | 约30分钟
        """.trimIndent()
        val result = extractItineraryMarkdown(raw)
        assertTrue("每天的景点不能被误删", result.contains("大明湖"))
        assertTrue(result.contains("趵突泉"))
        assertTrue("交通建议要保留", result.contains("# 交通建议"))
    }

    @Test
    fun `reasoning code fence is ignored when it does not look like itinerary`() {
        // 模型把思考放进代码围栏、正文不带围栏时，不能把思考围栏当成行程正文
        val raw = "```text\n先查一下车次，再安排……\n```\n\n# 第1天 · 济南\n## 上午\n- 景点: 大明湖"
        val result = extractItineraryMarkdown(raw)
        assertFalse("思考围栏不能当成正文", result.contains("先查一下车次"))
        assertTrue(result.startsWith("# 第1天"))
        assertTrue(result.contains("大明湖"))
    }

    @Test
    fun `responses answer keeps only the final message and drops search narration`() {
        // 联网搜索时，output[] 里会先出现一堆「搜索进度」说明（也是 message + output_text），
        // 最后才是真正的行程。旧逻辑会把说明也拼进来，导致自言自语被当成行程导入。
        val narration = JSONObject()
            .put("type", "message")
            .put("role", "assistant")
            .put(
                "content",
                JSONArray().put(
                    JSONObject()
                        .put("type", "output_text")
                        .put("text", "I need more specific information about the actual train schedules. Let me search more."),
                ),
            )
        val final = JSONObject()
            .put("type", "message")
            .put("role", "assistant")
            .put(
                "content",
                JSONArray().put(
                    JSONObject()
                        .put("type", "output_text")
                        .put(
                            "text",
                            "---\n旅行名称: 测试\n开始日期: 2026-05-01\n---\n\n# 第1天 · 西安\n## 上午\n- 景点: 兵马俑",
                        ),
                ),
            )
        val body = JSONObject()
            .put(
                "output",
                JSONArray()
                    .put(JSONObject().put("type", "web_search_call"))
                    .put(narration)
                    .put(JSONObject().put("type", "web_search_call"))
                    .put(final),
            )

        val cleaned = extractItineraryMarkdown(AiClient.extractResponsesAnswer(body.toString()))
        assertFalse("搜索过程的说明不能混进正文", cleaned.contains("Let me search"))
        assertTrue("要取到最后一条真正的行程", cleaned.contains("# 第1天"))
        assertTrue(cleaned.contains("兵马俑"))
    }

    @Test
    fun `responses answer with trailing output_text item wins over earlier narration`() {
        val narration = JSONObject()
            .put("type", "message")
            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "Let me search a bit more...")))
        val final = JSONObject()
            .put("type", "output_text")
            .put("text", "# 第1天 · 西安\n## 下午\n- 景点: 大雁塔")
        val body = JSONObject().put("output", JSONArray().put(narration).put(final))

        val answer = AiClient.extractResponsesAnswer(body.toString())
        assertFalse(answer.contains("Let me search"))
        assertTrue(answer.contains("大雁塔"))
    }

    @Test
    fun `responses answer prefers itinerary item over trailing politeness`() {
        // 模型在完整行程后面又补了一句客套话：要取行程那条，不能被客套话顶掉
        val itinerary = JSONObject()
            .put("type", "message")
            .put(
                "content",
                JSONArray().put(
                    JSONObject().put("type", "output_text").put("text", "# 第1天 · 西安\n## 上午\n- 景点: 兵马俑"),
                ),
            )
        val politeness = JSONObject()
            .put("type", "message")
            .put(
                "content",
                JSONArray().put(
                    JSONObject().put("type", "output_text").put("text", "希望这份行程对你有帮助！"),
                ),
            )
        val body = JSONObject().put("output", JSONArray().put(itinerary).put(politeness))

        val answer = AiClient.extractResponsesAnswer(body.toString())
        assertTrue("要取完整行程那条", answer.contains("兵马俑"))
        assertFalse("行程后的客套话不能顶掉行程", answer.contains("希望这份行程"))
    }

    @Test
    fun `responses answer picks the most complete message when itinerary is split`() {
        // 模型把回答拆成多条 message：第一条只有第1天（搜索中间结论），后面才是完整两天行程 + 车票
        val partial = JSONObject()
            .put("type", "message")
            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "# 第1天 · 西安\n## 上午\n- 景点: 兵马俑")))
        val webCall = JSONObject().put("type", "web_search_call")
        val full = JSONObject()
            .put("type", "message")
            .put(
                "content",
                JSONArray().put(
                    JSONObject().put(
                        "type", "output_text",
                    ).put("text", "# 第1天 · 西安\n## 上午\n- 景点: 兵马俑\n# 第2天 · 西安\n## 上午\n- 景点: 大雁塔\n# 车票\n- 车次: G26 | 北京西→西安北 | 2026-05-01 08:00 | 二等座"),
                ),
            )
        val closing = JSONObject()
            .put("type", "message")
            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "以上就是全部安排，祝旅途愉快！")))
        val body = JSONObject().put("output", JSONArray().put(partial).put(webCall).put(full).put(closing))

        val answer = AiClient.extractResponsesAnswer(body.toString())
        // 评分制应选中“两天 + 车票”最完整的那条，而不是最后的客套或第一段碎片
        assertTrue(answer.contains("大雁塔"))
        assertTrue(answer.contains("G26"))
        assertFalse(answer.contains("祝旅途愉快"))
    }

    @Test
    fun `pure narration without itinerary markers is rejected`() {
        // 整段既没有 第N天 也没有任何行程特征 → 是思考/说明文字，必须报错而不是原样返回
        try {
            extractItineraryMarkdown("I need more specific information. Let me search for actual train schedules first.")
            fail("纯说明文字必须被拒掉")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("没返回行程正文"))
        }
    }
}
