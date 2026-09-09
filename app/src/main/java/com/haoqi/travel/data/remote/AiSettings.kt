package com.haoqi.travel.data.remote

import android.content.Context

/**
 * AI 接口配置：存在本机 SharedPreferences。
 * 只使用豆包（火山方舟，联网搜索数据真实）；接口地址/模型名/推理接入点仍可手动改。
 * API Key 单独保存，重装/换 Key 互不影响。
 */
data class AiConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    /** 是否让 AI 先联网搜索再作答 */
    val webSearch: Boolean = false,
)

object AiSettings {

    data class Provider(val name: String, val baseUrl: String, val model: String)

    /** 只用豆包（火山方舟）：联网内容插件走 Responses API，返回真实车次/酒店，推荐默认 */
    val providers = listOf(
        // 模型名可用官方模型 ID（如 doubao-seed-2-0-lite-260428）或「推理接入点」ep-xxx；
        // 若调用报“模型不存在”，去方舟控制台确认已开通，或把创建的接入点 ep-xxx 填入「模型名称/推理接入点」框。
        // 想更省钱可临时改成 doubao-seed-character-260628（纯文本角色模型），但需实测它支持联网内容插件。
        Provider("豆包(火山方舟)", "https://ark.cn-beijing.volces.com/api/v3", "doubao-seed-2-0-lite-260428"),
    )

    private const val PREFS = "ai_settings"
    private const val KEY_PROVIDER = "provider_name"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_MODEL = "model"
    private const val KEY_API_KEY_LEGACY = "api_key" // 旧版只有一个 Key 时用的键
    private const val KEY_WEB_SEARCH = "web_search" // 联网搜索开关（实验功能）

    private fun keyFor(name: String) = "api_key_$name"

    /** 已下线的旧模型 ID：即使以前保存过也强制回落到当前默认，避免升级后还在用旧 ID 报「模型不存在」 */
    private val RETIRED_MODELS = setOf("doubao-seed-1-6-flash-250715", "doubao-seed-1-6-think-250715")

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
            model = if (stillValid) {
                if (savedModel.isBlank() || savedModel in RETIRED_MODELS) provider.model else savedModel
            } else provider.model,
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
