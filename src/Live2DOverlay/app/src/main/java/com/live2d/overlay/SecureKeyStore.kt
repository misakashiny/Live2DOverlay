package com.live2d.overlay

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * v1.7.12：LLM 凭据的加密存储。
 *
 * 为什么单独一个类：
 * 1. **密钥绝不落日志** —— 本类对外只暴露 `maskedKey()`（形如 `sk-…3f2a`），
 *    其余地方拿到 `apiKey()` 后不得再写进日志。
 * 2. **Keystore 可能不可用** —— 部分 ROM / 恢复出厂后 MasterKey 会失效，
 *    构造会抛异常。这里降级为「功能不可用」而不是崩溃：`available=false`，
 *    界面提示用户重新输入。
 * 3. 与 `OverlayConfig` 分开：那是**渲染配置**（要进 URL 参数），
 *    这是**凭据**，不该出现在 URL / 日志 / 页面上。
 *
 * 存储后端：`EncryptedSharedPreferences`（AES-256-GCM，密钥由 Android Keystore 托管，
 * 不出 TEE/StrongBox）。文件名与普通 prefs 分开，避免混用。
 */
object SecureKeyStore {

    private const val FILE_NAME = "l2d_secure_creds"
    private const val KEY_API = "llm_api_key"
    private const val KEY_BASE_URL = "llm_base_url"
    private const val KEY_MODEL = "llm_model"

    /**
     * 默认值：**方案 A —— 一个百炼 Key 同时管 LLM 与 TTS**。
     *
     * - LLM 走百炼的 **OpenAI 兼容模式**端点（`/compatible-mode/v1`），模型 `qwen-plus`
     * - TTS 走百炼的 `dashscope.aliyuncs.com`（见 [TtsClient]）
     *
     * 这样用户只需填**一个 Key**，不用维护两套凭据。
     * 若要用别家（如 DeepSeek），把 BaseURL 改成对应端点即可 —— 界面已可改。
     */
    const val DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    const val DEFAULT_MODEL = "qwen-plus"

    // ---- v1.7.16：TTS（百炼 Qwen-TTS）----
    // 端点固定、不需要 WorkspaceId（CosyVoice v3 需要，故未选它）
    const val KEY_TTS_BASE_URL = "tts_base_url"
    const val KEY_TTS_MODEL = "tts_model"
    const val KEY_TTS_VOICE = "tts_voice"

    /**
     * TTS 配置。**复用同一个百炼 API Key**（`KEY_API`）——
     * 百炼的 LLM（兼容模式）与 TTS 用同一个 Key，不必让用户填两遍。
     */
    fun ttsBaseUrl(context: Context): String =
        store(context)?.getString(KEY_TTS_BASE_URL, null)?.takeIf { it.isNotBlank() }
            ?: TtsClient.DEFAULT_BASE_URL

    fun ttsModel(context: Context): String =
        store(context)?.getString(KEY_TTS_MODEL, null)?.takeIf { it.isNotBlank() }
            ?: TtsClient.DEFAULT_MODEL

    fun ttsVoice(context: Context): String =
        store(context)?.getString(KEY_TTS_VOICE, null)?.takeIf { it.isNotBlank() }
            ?: TtsClient.DEFAULT_VOICE

    /** TTS 是否可用（复用 LLM 的 Key） */
    fun hasTts(context: Context): Boolean = hasKey(context)

    /** 保存 TTS 配置（Key 沿用 `save()` 的那份，不在这里改） */
    fun saveTts(context: Context, baseUrl: String, model: String, voice: String): Boolean {
        val p = store(context) ?: return false
        return try {
            val e = p.edit()
            if (baseUrl.isBlank()) e.remove(KEY_TTS_BASE_URL) else e.putString(KEY_TTS_BASE_URL, baseUrl.trim())
            if (model.isBlank()) e.remove(KEY_TTS_MODEL) else e.putString(KEY_TTS_MODEL, model.trim())
            if (voice.isBlank()) e.remove(KEY_TTS_VOICE) else e.putString(KEY_TTS_VOICE, voice.trim())
            val ok = e.commit()
            L2DLog.i(L2DLog.Mod.AI, "TTS 配置已保存",
                "ok=$ok baseUrl=$baseUrl model=$model voice=$voice")
            ok
        } catch (t: Throwable) {
            L2DLog.e(L2DLog.Mod.AI, "TTS 配置保存失败", "err=${t.javaClass.simpleName}", t)
            false
        }
    }

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var initFailed = false

    /**
     * 取（并缓存）加密 prefs。失败返回 null，并把 `initFailed` 置位 ——
     * 调用方据此提示用户，而不是反复重试。
     */
    private fun store(context: Context): SharedPreferences? {
        prefs?.let { return it }
        if (initFailed) return null
        synchronized(this) {
            prefs?.let { return it }
            if (initFailed) return null
            return try {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val p = EncryptedSharedPreferences.create(
                    context.applicationContext,
                    FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                prefs = p
                L2DLog.i(L2DLog.Mod.AI, "凭据加密存储已就绪", "file=$FILE_NAME scheme=AES256_GCM")
                p
            } catch (t: Throwable) {
                initFailed = true
                L2DLog.e(L2DLog.Mod.AI, "凭据加密存储不可用", "err=${t.javaClass.simpleName}", t)
                null
            }
        }
    }

    /** 加密存储是否可用（不可用时界面应提示重新输入） */
    fun available(context: Context): Boolean = store(context) != null

    /** 是否已配置好可用的 Key */
    fun hasKey(context: Context): Boolean = !apiKey(context).isNullOrBlank()

    fun apiKey(context: Context): String? =
        store(context)?.getString(KEY_API, null)?.takeIf { it.isNotBlank() }

    fun baseUrl(context: Context): String =
        store(context)?.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL

    fun model(context: Context): String =
        store(context)?.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    /**
     * 保存凭据。`apiKey` 传 null 表示**不改动**已有的 Key
     * （界面上留空就是「不改」，避免用户只改 baseUrl 时把 Key 清掉）。
     *
     * @return 是否写入成功
     */
    fun save(context: Context, apiKey: String?, baseUrl: String, model: String): Boolean {
        val p = store(context) ?: return false
        return try {
            val e = p.edit()
            if (apiKey != null) {
                if (apiKey.isBlank()) e.remove(KEY_API) else e.putString(KEY_API, apiKey.trim())
            }
            if (baseUrl.isBlank()) e.remove(KEY_BASE_URL) else e.putString(KEY_BASE_URL, baseUrl.trim())
            if (model.isBlank()) e.remove(KEY_MODEL) else e.putString(KEY_MODEL, model.trim())
            val ok = e.commit()
            // ★ 只记掩码，绝不记原文
            L2DLog.i(L2DLog.Mod.AI, "凭据已保存",
                "ok=$ok key=${if (apiKey == null) "(未改动)" else masked(apiKey)} " +
                        "baseUrl=${baseUrl.trim()} model=${model.trim()}")
            ok
        } catch (t: Throwable) {
            L2DLog.e(L2DLog.Mod.AI, "凭据保存失败", "err=${t.javaClass.simpleName}", t)
            false
        }
    }

    /** 清除全部凭据 */
    fun clear(context: Context): Boolean {
        val p = store(context) ?: return false
        return try {
            val ok = p.edit().clear().commit()
            L2DLog.w(L2DLog.Mod.AI, "凭据已清除", "ok=$ok")
            ok
        } catch (t: Throwable) {
            L2DLog.e(L2DLog.Mod.AI, "凭据清除失败", "err=${t.javaClass.simpleName}", t)
            false
        }
    }

    /**
     * 生成掩码用于日志：保留前 3 位与后 4 位，中间固定 `***`。
     * 太短的串直接全掩。
     */
    fun masked(key: String?): String {
        val k = key?.trim().orEmpty()
        if (k.isEmpty()) return "(空)"
        if (k.length <= 8) return "***"
        return k.take(3) + "***" + k.takeLast(4)
    }
}
