package com.haoqi.travel.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

/**
 * 调 AI 接口。不引入第三方网络库，用系统自带 HttpURLConnection + org.json 完成。
 *
 * 只对接豆包（火山方舟）：
 *  - 普通对话/生成：[chat] 走 `/chat/completions`（非流式）；
 *  - 联网搜索：走 OpenAI 兼容的 Responses API `/responses` + 内置 `web_search` 工具（需在方舟
 *    开通「联网内容插件」），SSE 流式；联网内容插件只支持 Responses API，在 chat/completions 里
 *    传 web_search 工具会报 MissingParameter: tools.function.parameter。
 *
 * 规划页用「两步法」调用 [chatWithWebSearch]（factsMode=true 先收真实车票/酒店清单，
 * 再让规划模型只引用该清单），配合 [extractFactsMarkdown]/[extractItineraryMarkdown] 清洗，
 * 从源头杜绝车次/酒店名幻觉。
 *
 * 注意：思考模型会在正答外返回「思考过程」（reasoning）。它要么是独立字段
 * （`reasoning_content` / `output[]` 里的 `reasoning` 条目），要么被塞在正文前面。
 * 下面各处都做了过滤：只取最终回答，再通过 [extractItineraryMarkdown] 把行程正文切出来。
 */
object AiClient {

    private const val SYSTEM_PROMPT =
        "你是专业的中国旅行规划助手。只输出行程 Markdown，严格套用用户给出的模板；" +
            "不要输出思考过程、分析、解释、寒暄，也不要代码围栏。" +
            "使用联网搜索时：先查所需资料，但搜索过程中不要输出任何进度说明或自言自语；" +
            "城际交通（车票/航班）和酒店都要联网搜索真实信息；" +
            "所有搜索结束后，把完整行程**合并成一条最终回答**一次性输出（不要拆成多条消息、不要分段输出）。"

    /** 带 HTTP 状态码的异常，方便按状态码决定要不要换个路径重试 */
    private class HttpStatusException(val code: Int, message: String) : IOException(message)

    suspend fun chat(config: AiConfig, prompt: String, readTimeoutMs: Long = 150_000): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("model", config.model)
                .put("temperature", 0.7)
                .put("stream", false)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                        .put(JSONObject().put("role", "user").put("content", prompt)),
                )
            val json = JSONObject(post(config, "/chat/completions", body, readTimeoutMs))
        val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        // 只取最终回答 content，绝不把 reasoning_content（思考过程）当正文
        val content = message?.optString("content").orEmpty()
        if (content.isBlank()) {
            // 有思考没正文时，把原因讲清楚，而不是让用户看到莫名其妙的空结果
            val hadReasoning = message?.optString("reasoning_content").orEmpty().isNotBlank()
            throw IOException(if (hadReasoning) "模型只返回了思考过程、没有行程正文，请在「更多 → AI 生成设置」把模型换成不带思考的版本（如 doubao-seed-2-0-lite 或你的推理接入点）" else "AI 返回内容为空")
        }
        extractItineraryMarkdown(content)
    }

    /**
     * 用联网搜索让模型作答（豆包 · 火山方舟）：OpenAI 兼容的 Responses API
     * `/responses` + tools web_search（「联网内容插件」只支持 Responses API，
     * 在 chat/completions 传 web_search 会报 MissingParameter: tools.function.parameter）。
     * SSE 流式保活连接（5G 下长请求易被掐断）。
     * [factsMode]=true 时返回「事实清单」原文（第一步情报收集用）。
     */
    suspend fun chatWithWebSearch(
        config: AiConfig,
        prompt: String,
        readTimeoutMs: Long = 120_000,
        factsMode: Boolean = false,
    ): String =
        withContext(Dispatchers.IO) {
            responsesSearch(
                config = config,
                prompt = prompt,
                readTimeoutMs = readTimeoutMs,
                factsMode = factsMode,
                tool = JSONObject().put("type", "web_search").put("max_keyword", 3),
                extraInstructions = "用户会要求你联网查询真实信息，请只输出查询到的清单本身，不要行程、不要寒暄。",
            )
        }

    /**
     * Responses API 联网搜索实现：流式请求 → 校验 web_search_call 确实执行过 → 抽正文/事实清单。
     * 工具触发后服务端执行搜索，返回里带 web_search_call 记录和带 URL 引用的搜索结果，
     * 正文是模型基于搜索结果给出的最终回答。
     */
    private suspend fun responsesSearch(
        config: AiConfig,
        prompt: String,
        readTimeoutMs: Long,
        factsMode: Boolean,
        tool: JSONObject,
        extraInstructions: String,
    ): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("model", config.model)
                .put(
                    "instructions",
                    SYSTEM_PROMPT + if (extraInstructions.isBlank()) "" else "\n$extraInstructions",
                )
                .put("input", prompt)
                .put("stream", true)
                .put("max_output_tokens", 8192)
                .put("tools", JSONArray().put(tool))

            val text = try {
                postSse(config, "/responses", body, readTimeoutMs)
            } catch (e: HttpStatusException) {
                // 有些部署把 Responses API 挂在 /v1 下，路径不对时换一个再试一次
                if (e.code == 404 || e.code == 405) {
                    postSse(config, "/v1/responses", body, readTimeoutMs)
                } else throw e
            }

            // 验证真的联网搜索过：output[] 里必须有 web_search_call 记录
            val json = JSONObject(text)
            val out = json.optJSONArray("output")
            val hadSearch = out != null && (0 until out.length()).any {
                out.optJSONObject(it)?.optString("type") == "web_search_call"
            }
            if (!hadSearch) {
                throw IOException("本次响应里没有联网搜索记录（服务端未执行搜索），不能当联网结果用")
            }
            val answer = extractResponsesAnswer(text)
            if (factsMode) extractFactsMarkdown(answer) else extractItineraryMarkdown(answer)
        }

    /**
     * 从 Responses API 返回的 JSON 里只取「最终回答」正文，跳过搜索过程中的进度说明。
     *
     * 联网搜索时模型可能输出多条 message（搜索中间结论、分段行程、收尾客套话）。
     * 这里给每条 message 按「行程特征」打分（第N天标题/地点行/车票），**取分数最高那条**——
     * 即最完整的行程正文，而不是简单取最后一条（最后一条可能只是碎片或客套话）。
     */
    internal fun extractResponsesAnswer(text: String): String {
        val json = JSONObject(text)

        // 1) 便捷字段 output_text（部分实现直接给拼好的文本）
        var content = json.optString("output_text", "").trim()

        // 2) 标准结构：output[] 里的 message / output_text 条目，按行程特征评分取最高
        if (content.isBlank()) {
            val out = json.optJSONArray("output")
            if (out != null) {
                var best = ""
                var bestScore = 0
                var lastNonBlank = ""
                for (i in 0 until out.length()) {
                    val item = out.optJSONObject(i) ?: continue
                    val t = when (item.optString("type")) {
                        "output_text" -> item.optString("text", "")
                        "message" -> messageOutputText(item)
                        else -> ""
                    }.trim()
                    if (t.isEmpty()) continue
                    if (lastNonBlank.isEmpty()) lastNonBlank = t
                    val score = itineraryScore(t)
                    if (score > bestScore) {
                        bestScore = score
                        best = t
                    }
                }
                content = if (bestScore > 0) best else lastNonBlank
            }
        }

        // 3) 万一服务端仍按 chat/completions 风格返回
        if (content.isBlank()) {
            content = json.optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        }
        return content
    }

    /** 行程特征评分：第N天标题权重最高，地点/车票行次之；用于挑出最完整的那条 message */
    private fun itineraryScore(s: String): Int {
        var score = 0
        score += Regex("""#\s*第\s*\d+\s*天""").findAll(s).count() * 10
        if (s.contains("旅行名称")) score += 5
        Regex("""-\s*(景点|景区|饭店|餐厅|酒店|宾馆|民宿)\s*[:：]""").findAll(s).forEach { score += 2 }
        Regex("""#\s*(交通建议|车票)""").findAll(s).count().let { score += it * 8 }
        Regex("""车次\s*[:：]""").findAll(s).forEach { score += 3 }
        return score
    }

    /** 从 Responses API 的 message 条目里取出最终正文（output_text 文本块），跳过 reasoning / web_search_call */
    private fun messageOutputText(item: JSONObject): String {
        val blocks = item.optJSONArray("content")
        // 有的实现 content 直接是字符串而不是数组
        if (blocks == null) return item.optString("content", "")
        val sb = StringBuilder()
        for (j in 0 until blocks.length()) {
            val b = blocks.optJSONObject(j) ?: continue
            val t = b.optString("type", "")
            if (t.isEmpty() || t == "output_text" || t == "text") sb.append(b.optString("text", ""))
        }
        return sb.toString()
    }

    /** 发一次 POST（DNS/连接偶发失败自动等 2 秒重试一次）；非 2xx 抛 [HttpStatusException] */
    private fun post(config: AiConfig, path: String, body: JSONObject, readTimeoutMs: Long = 60_000): String =
        try {
            postOnce(config, path, body, readTimeoutMs)
        } catch (e: UnknownHostException) {
            // 域名解析偶发失败（切网/弱网/路由器 DNS 抽风都常见）：等 2 秒再试一次
            Thread.sleep(2_000)
            try {
                postOnce(config, path, body, readTimeoutMs)
            } catch (e2: UnknownHostException) {
                throw IOException(
                    "连不上 AI 服务（域名解析失败）。请检查网络：可关闭再打开 WiFi/流量，等几秒后重试",
                    e2,
                )
            } catch (e2: ConnectException) {
                throw IOException("网络连接失败，请检查手机网络后重试", e2)
            }
        } catch (e: ConnectException) {
            throw IOException("网络连接失败，请检查手机网络后重试", e)
        }

    private fun postOnce(config: AiConfig, path: String, body: JSONObject, readTimeoutMs: Long): String {
        val url = URL("${config.baseUrl.trimEnd('/')}$path")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30_000
            conn.readTimeout = readTimeoutMs.toInt()
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw HttpStatusException(code, "AI 接口返回 $code：${text.take(300)}${arkModelHint(code, url.host)}")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** 豆包(火山方舟)接口报 404 时，多半是模型 ID 填错/未开通——把去哪找正确 ID 直接写进错误提示 */
    private fun arkModelHint(code: Int, host: String): String {
        if (code != 404 || !host.endsWith("volces.com")) return ""
        return "（豆包提示：模型ID不存在或未开通。请到火山方舟控制台确认模型已开通，" +
            "把准确的「模型ID」或新建的「推理接入点」ep-xxx 填入 App 的「模型名称 / 推理接入点」框；" +
            "联网搜索还需在方舟开通「联网内容插件」）"
    }

    /**
     * SSE 流式 POST：逐行读 `data: {json}` 事件，到 response.completed / response.incomplete 时
     * 返回该事件里完整的 response JSON（后续解析逻辑与非流式完全一致）。
     * 流式让连接持续有数据，避免长请求静默期被运营商/系统掐断（5G 的 Software caused connection abort）。
     */
    private fun postSse(config: AiConfig, path: String, body: JSONObject, readTimeoutMs: Long): String =
        try {
            postSseOnce(config, path, body, readTimeoutMs)
        } catch (e: UnknownHostException) {
            Thread.sleep(2_000)
            try {
                postSseOnce(config, path, body, readTimeoutMs)
            } catch (e2: UnknownHostException) {
                throw IOException("连不上 AI 服务（域名解析失败）。请检查网络后重试", e2)
            } catch (e2: ConnectException) {
                throw IOException("网络连接失败，请检查手机网络后重试", e2)
            }
        } catch (e: ConnectException) {
            throw IOException("网络连接失败，请检查手机网络后重试", e)
        }

    private fun postSseOnce(config: AiConfig, path: String, body: JSONObject, readTimeoutMs: Long): String {
        val url = URL("${config.baseUrl.trimEnd('/')}$path")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30_000
            conn.readTimeout = readTimeoutMs.toInt()
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw HttpStatusException(code, "AI 接口返回 $code：${err.take(300)}${arkModelHint(code, url.host)}")
            }

            val reader = conn.inputStream.bufferedReader()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line!!.trim()
                if (!l.startsWith("data:")) continue
                val payload = l.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                val evt = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                when (evt.optString("type")) {
                    "response.completed", "response.incomplete" -> {
                        val resp = evt.optJSONObject("response")
                        if (resp != null) return resp.toString()
                    }
                    "response.failed", "error" -> {
                        val msg = evt.optJSONObject("error")?.optString("message")
                            ?: evt.optJSONObject("response")?.optJSONObject("error")?.optString("message")
                            ?: "流式响应失败"
                        throw IOException("联网搜索失败：$msg")
                    }
                }
            }
            throw IOException("联网搜索连接中断（流式响应未完成），请重试")
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * 从 AI 返回的原始文本里抽出「行程 Markdown」正文。
 *
 * 模型有时会把思考过程写在正文前面、或把正文包进代码围栏，直接导入就会把思考也当成行程。
 * 这里做两道清洗：
 *  1. 有代码围栏时，只取「确实像行程」的那个围栏（含 # 第N天 / 旅行名称 / 景点行），
 *     避免误把思考/搜索记录的围栏当成正文；
 *  2. 再从行程真正开始的地方（`# 第N天`，其次 `---`）切掉前面的杂音。
 *
 * 注意：绝不能拿「# 交通建议 / # 车票」当开头锚点——它们出现在每天行程之后，
 * 一旦 AI 没写 YAML 头或 # 第N天，就会把前面的整段每日行程误删，只剩车票。
 */
/** 这段文本像不像一份行程（有 第N天 标题 / YAML 名 / 地点列表行 / 时段标题） */
internal fun looksLikeItinerary(s: String): Boolean =
    Regex("""#\s*第\s*\d+\s*天""").containsMatchIn(s) ||
        s.contains("旅行名称") ||
        Regex("""-\s*(景点|景区|饭店|餐厅|酒店|宾馆|民宿)\s*[:：]""").containsMatchIn(s) ||
        Regex("""##\s*(上午|午餐|下午|晚餐|晚上)""").containsMatchIn(s)

/**
 * 从「情报收集」的回答里裁出事实清单：丢掉前面的“我来帮你查…让我整理…”等叙述，
 * 从第一个事实标题（### / ##）或列表行（- 车次 / - 酒店 / - 线路）开始保留。
 * 与 [extractItineraryMarkdown] 不同：facts 没有 # 第N天 锚点，不能用行程逻辑清洗。
 */
internal fun extractFactsMarkdown(raw: String): String {
    var content = raw.trim()
    content = content.removePrefix("```markdown").removePrefix("```md").removePrefix("```").removeSuffix("```").trim()
    val lines = content.lines()
    val idx = lines.indexOfFirst {
        val t = it.trim()
        t.startsWith("###") || t.startsWith("##") ||
            t.startsWith("- 车次") || t.startsWith("- 酒店") || t.startsWith("- 线路")
    }
    if (idx > 0) content = lines.drop(idx).joinToString("\n").trim()
    if (content.isBlank()) throw IOException("联网核实返回为空")
    return content
}

internal fun extractItineraryMarkdown(raw: String): String {
    var content = raw.trim()
    if (content.isEmpty()) throw IOException("AI 返回内容为空")

    // 1) 代码围栏：可能有多个，只取「内容像行程」的那个
    val fenceBody = Regex("```[^\\r\\n]*[\\r\\n]([\\s\\S]*?)```")
        .findAll(content)
        .map { it.groupValues[1] }
        .firstOrNull { b -> looksLikeItinerary(b) }
    if (fenceBody != null) content = fenceBody.trim()

    // 2) 去掉零散的围栏标记
    content = content
        .removePrefix("```markdown").removePrefix("```md").removePrefix("```")
        .removeSuffix("```").trim()

    // 3) 裁掉正文前面的「思考/寒暄」：行程一定以 # 第N天（或 --- YAML）开头
    val lines = content.lines()
    val dayIdx = lines.indexOfFirst { it.trim().matches(Regex("""#\s*第\s*\d+\s*天.*""")) }
    val yamlIdx = lines.indexOfFirst { it.trim() == "---" }
    val start = when {
        dayIdx >= 0 -> dayIdx
        yamlIdx >= 0 -> yamlIdx
        else -> -1
    }
    if (start >= 0) {
        content = lines.drop(start).joinToString("\n").trim()
    } else if (!looksLikeItinerary(content)) {
        // 整段既没有 # 第N天 / --- ，也没有任何行程特征 → 是思考或说明文字，绝不能当行程用
        throw IOException("AI 没返回行程正文（只回了思考或说明文字），请重试")
    }
    if (content.isBlank()) throw IOException("AI 返回内容为空")

    return content
}
