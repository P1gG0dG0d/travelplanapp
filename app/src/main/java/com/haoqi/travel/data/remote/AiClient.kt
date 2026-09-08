package com.haoqi.travel.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 调 AI 接口。不引入第三方网络库，用系统自带 HttpURLConnection + org.json 完成。
 *
 * 两种调用方式：
 *  - [chat]：普通对话补全 `/chat/completions`（默认，稳定）
 *  - [chatWithWebSearch]：DeepSeek Responses API `/responses` + 内置 web_search 工具（实验功能，
 *    让模型自己联网查资料）。接口不通时由调用方回退到 [chat]，不影响正常生成。
 *
 * 注意：DeepSeek 会在正答之外返回一段「思考过程」（reasoning）。它要么是独立字段
 * （`reasoning_content` / `output[]` 里的 `reasoning` 条目），要么被塞在正文前面。
 * 下面两处都做了过滤：只取最终回答，再通过 [extractItineraryMarkdown] 把行程正文切出来。
 */
object AiClient {

    private const val SYSTEM_PROMPT =
        "你是专业的中国旅行规划助手。只输出行程 Markdown，严格套用用户给出的模板；" +
            "不要输出思考过程、分析、解释、寒暄，也不要代码围栏。" +
            "使用联网搜索时：先查所需资料，但搜索过程中不要输出任何进度说明或自言自语；" +
            "每条城际线路（去程、城际换乘、返程）各至少搜一次，某条没查到可换关键词补搜一次，" +
            "总次数不超过 6 次；所有线路都查到（或确认查不到）后立刻停止搜索，" +
            "一次性输出完整行程 Markdown。"

    /** 带 HTTP 状态码的异常，方便按状态码决定要不要换个路径重试 */
    private class HttpStatusException(val code: Int, message: String) : IOException(message)

    suspend fun chat(config: AiConfig, prompt: String): String = withContext(Dispatchers.IO) {
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
        val json = JSONObject(post(config, "/chat/completions", body))
        val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        // 只取最终回答 content，绝不把 reasoning_content（思考过程）当正文
        val content = message?.optString("content").orEmpty()
        if (content.isBlank()) {
            // 有思考没正文时，把原因讲清楚，而不是让用户看到莫名其妙的空结果
            val hadReasoning = message?.optString("reasoning_content").orEmpty().isNotBlank()
            throw IOException(if (hadReasoning) "模型只返回了思考过程、没有行程正文，请在「更多 → AI 生成设置」把模型换成 deepseek-chat" else "AI 返回内容为空")
        }
        extractItineraryMarkdown(content)
    }

    /**
     * 用 Responses API 让模型带着联网搜索作答。
     * 它的请求体和响应体都跟 /chat/completions 不一样，这里对响应做了多种形状的兜底解析，
     * 并且只收 `output_text` 文本块——`reasoning`、`web_search_call` 等一律跳过。
     */
    suspend fun chatWithWebSearch(config: AiConfig, prompt: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", config.model)
            .put("instructions", SYSTEM_PROMPT)
            .put("input", prompt)
            .put("stream", false)
            .put("max_output_tokens", 8192)
            .put("tools", JSONArray().put(JSONObject().put("type", "web_search")))

        val text = try {
            post(config, "/responses", body)
        } catch (e: HttpStatusException) {
            // 有些部署把 Responses API 挂在 /v1 下，路径不对时换一个再试一次
            if (e.code == 404 || e.code == 405) post(config, "/v1/responses", body) else throw e
        }
        extractItineraryMarkdown(extractResponsesAnswer(text))
    }

    /**
     * 从 Responses API 返回的 JSON 里只取「最终回答」正文，跳过搜索过程中的进度说明。
     *
     * 联网搜索时，模型会在真正的行程前面输出一堆「搜索进度」说明（也是 output_text，
     * 例如 "I need more information… Let me search more"），有时还会在行程后面补一句客套话。
     * 所以这里从后往前找，优先取【最后一条长得像行程】的正文；
     * 一条都不像时才退而取最后一条非空正文（随后会被 [extractItineraryMarkdown] 拒掉）。
     */
    internal fun extractResponsesAnswer(text: String): String {
        val json = JSONObject(text)

        // 1) 便捷字段 output_text（部分实现直接给拼好的文本）
        var content = json.optString("output_text", "").trim()

        // 2) 标准结构：output[] 里的 message / output_text 条目，优先取最后一条「像行程」的
        if (content.isBlank()) {
            val out = json.optJSONArray("output")
            if (out != null) {
                var lastNonBlank = ""
                for (i in out.length() - 1 downTo 0) {
                    val item = out.optJSONObject(i) ?: continue
                    val t = when (item.optString("type")) {
                        "output_text" -> item.optString("text", "")
                        "message" -> messageOutputText(item)
                        else -> ""
                    }.trim()
                    if (t.isEmpty()) continue
                    if (looksLikeItinerary(t)) {
                        content = t
                        break
                    }
                    if (lastNonBlank.isEmpty()) lastNonBlank = t
                }
                if (content.isBlank()) content = lastNonBlank
            }
        }

        // 3) 万一服务端仍按 chat/completions 风格返回
        if (content.isBlank()) {
            content = json.optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        }
        return content
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

    /** 发一次 POST，返回响应体文本；非 2xx 抛 [HttpStatusException] */
    private fun post(config: AiConfig, path: String, body: JSONObject): String {
        val url = URL("${config.baseUrl.trimEnd('/')}$path")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30_000
            conn.readTimeout = 180_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw HttpStatusException(code, "AI 接口返回 $code：${text.take(300)}")
            }
            return text
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
