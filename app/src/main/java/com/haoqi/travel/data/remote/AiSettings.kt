package com.haoqi.travel.data.remote

import android.content.Context

/**
 * AI 接口配置：存在本机 SharedPreferences。
 * 只用 DeepSeek（OpenAI 兼容接口），服务商/模型名仍可手动改。
 * API Key 按「服务商」分开保存，方便以后再加别的服务商时互不串。
 */
data class AiConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    /** 是否让 AI 先联网搜索再作答（DeepSeek Responses API，实验功能） */
    val webSearch: Boolean = false,
)

object AiSettings {

    data class Provider(val name: String, val baseUrl: String, val model: String, val models: List<String> = emptyList())

    val providers = listOf(
        Provider(
            "DeepSeek", "https://api.deepseek.com", "deepseek-chat",
            // deepseek-chat：便宜稳定；deepseek-v4-flash：更聪明，联网搜索（尤其查机票）建议用它
            models = listOf("deepseek-chat", "deepseek-v4-flash"),
        ),
    )

    private const val PREFS = "ai_settings"
    private const val KEY_PROVIDER = "provider_name"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_MODEL = "model"
    private const val KEY_API_KEY_LEGACY = "api_key" // 旧版只有一个 Key 时用的键
    private const val KEY_WEB_SEARCH = "web_search" // 联网搜索开关（实验功能）

    private fun keyFor(name: String) = "api_key_$name"

    /** 读某个服务商自己的 Key（没填过就返回空串） */
    fun loadKey(context: Context, providerName: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(keyFor(providerName), "").orEmpty()

    fun load(context: Context): AiConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedName = p.getString(KEY_PROVIDER, "").orEmpty()
        // 只认当前列表里还存在的服务商；之前存过、现在已下架的，回退到默认那家
        val provider = providers.firstOrNull { it.name == savedName } ?: providers.first()
        val key = p.getString(keyFor(provider.name), "").orEmpty()
            .ifBlank { p.getString(KEY_API_KEY_LEGACY, "").orEmpty() }
        val savedBase = p.getString(KEY_BASE_URL, "").orEmpty()
        val savedModel = p.getString(KEY_MODEL, "").orEmpty()
        // 仅当保存的服务商仍然有效时，才沿用你自定义过的地址/模型
        val stillValid = provider.name == savedName
        return AiConfig(
            baseUrl = if (stillValid) savedBase.ifBlank { provider.baseUrl } else provider.baseUrl,
            model = if (stillValid) savedModel.ifBlank { provider.model } else provider.model,
            apiKey = key,
            webSearch = p.getBoolean(KEY_WEB_SEARCH, false),
        )
    }

    /** 联网搜索开关，独立存取，改它不用重填 API Key */
    fun isWebSearchEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WEB_SEARCH, false)

    fun setWebSearchEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WEB_SEARCH, enabled)
            .apply()
    }

    fun save(context: Context, providerName: String, baseUrl: String, model: String, apiKey: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROVIDER, providerName)
            .putString(KEY_BASE_URL, baseUrl.trim())
            .putString(KEY_MODEL, model.trim())
            .putString(keyFor(providerName), apiKey.trim())
            .apply()
    }

    fun hasKey(context: Context): Boolean = load(context).apiKey.isNotBlank()
}
