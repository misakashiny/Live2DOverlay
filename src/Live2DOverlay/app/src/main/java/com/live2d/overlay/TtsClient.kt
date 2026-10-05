package com.live2d.overlay

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * v1.7.16：阿里云百炼（DashScope）Qwen-TTS 客户端。
 *
 * ## 为什么选 Qwen-TTS 而不是 CosyVoice v3
 *
 * 官方文档（[非实时语音合成](https://help.aliyun.com/zh/model-studio/non-realtime-tts-user-guide)）里
 * 两个模型系列的**端点形态不同**：
 *
 * | 系列 | 端点 | 额外要求 |
 * |---|---|---|
 * | CosyVoice v3 | `https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer` | **需要 WorkspaceId** + 北京地域 Key |
 * | Qwen-TTS | `https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation` | 端点固定，**不需要 WorkspaceId** |
 *
 * 选 Qwen-TTS 的理由：**少一个配置项**（本项目设置界面只有 Key / BaseURL / Model 三格），
 * 且端点固定、不限定地域。
 *
 * ## 为什么要把音频下载下来转 data: URL
 *
 * 页面侧用 **WebAudio `AnalyserNode`** 做 LipSync（见 `live2d_decor.html` 的
 * `createWebAudioAnalyzer`）。而 `createMediaElementSource` **要求音频同源或带 CORS 头**，
 * 否则**输出静音**（WebAudio 安全限制）。阿里云返回的是 OSS 链接，CORS 行为不确定，
 * 所以必须由 Kotlin 下载后再转 `data:audio/wav;base64,...` 传给页面。
 *
 * ## 线程模型
 *
 * 单线程 Executor —— TTS 请求 + 下载两步串行，并发没有意义（会互相抢口型）。
 */
object TtsClient {

    /** 默认模型与音色（用户偏好的三个：Serena 苏瑶 / Momo 茉兔 / Bella 萌宝） */
    const val DEFAULT_MODEL = "qwen3-tts-flash"
    const val DEFAULT_VOICE = "Serena"
    const val DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com"

    /** 用户挑的三个音色（界面下拉用） */
    val VOICE_PRESETS = listOf(
        "Serena" to "苏瑶",
        "Momo" to "茉兔",
        "Bella" to "萌宝"
    )

    /** data URL 体积上限：超过就不传页面（base64 会膨胀 1/3，WebView 传大字符串会卡） */
    private const val MAX_DATA_URL_BYTES = 2_400_000

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "l2d-tts").apply { isDaemon = true }
    }

    data class Speech(
        val dataUrl: String,
        val mime: String,
        val bytes: Int,
        val latencyMs: Long
    )

    fun interface Callback {
        fun onResult(speech: Speech?, error: String?)
    }

    /**
     * 合成一段语音，回调里给的是可直接喂给页面 `__mikuLive2DSpeak()` 的 data URL。
     *
     * @param text 要合成的文本（建议是 LLM 的回复）
     */
    fun synthesize(context: Context, text: String, cb: Callback) {
        val apiKey = SecureKeyStore.apiKey(context)
        if (apiKey.isNullOrBlank()) {
            L2DLog.w(L2DLog.Mod.AI, "TTS 未配置", "缺少 API Key")
            cb.onResult(null, "未配置 API Key")
            return
        }
        val t = text.trim()
        if (t.isEmpty()) {
            cb.onResult(null, "文本为空")
            return
        }
        val baseUrl = SecureKeyStore.ttsBaseUrl(context).trimEnd('/')
        val model = SecureKeyStore.ttsModel(context)
        val voice = SecureKeyStore.ttsVoice(context)
        L2DLog.i(L2DLog.Mod.AI, "TTS 请求开始",
            "model=$model voice=$voice chars=${t.length} key=${SecureKeyStore.masked(apiKey)}")

        io.execute {
            val t0 = System.currentTimeMillis()
            try {
                val body = JSONObject()
                    .put("model", model)
                    .put("input", JSONObject()
                        .put("text", t)
                        .put("voice", voice)
                        // ★ v1.7.17：用 mp3 而不是 wav。
                        //   实测 wav 24kHz 三秒 = 244KB，base64 后 332K 字符；
                        //   而 Java String 是 UTF-16，塞进 Intent extra 时按 665KB 算，
                        //   顶到 Binder 1MB 事务上限 → 语音投递失败（服务侧一条日志都没有）。
                        //   mp3 同长度约 1/6 体积，Intent 与 evaluateJavascript 都轻松。
                        .put("format", SecureKeyStore.ttsFormat(context))
                        // 24kHz → 16kHz：语音够用，体积降 1/3，缓解 Intent/Binder 压力
                        .put("sample_rate", SecureKeyStore.ttsSampleRate(context)))
                    .toString()
                val url = "$baseUrl/api/v1/services/aigc/multimodal-generation/generation"
                val resp = postJson(url, apiKey, body)
                val audioUrl = extractAudioUrl(resp)
                if (audioUrl.isNullOrBlank()) {
                    throw IllegalStateException("响应里没有音频地址: ${resp.take(300)}")
                }
                // 下载音频 → base64 → data URL（页面 WebAudio 需要同源）
                val (bytes, mime) = download(audioUrl)
                if (bytes.isEmpty()) throw IllegalStateException("音频下载为空")
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val dataUrl = "data:$mime;base64,$b64"
                if (dataUrl.length > MAX_DATA_URL_BYTES) {
                    throw IllegalStateException("音频过大（${dataUrl.length} 字符），超过页面传输上限")
                }
                val latency = System.currentTimeMillis() - t0
                L2DLog.i(L2DLog.Mod.AI, "TTS 完成",
                    "音频=${bytes.size}B mime=$mime dataUrl=${dataUrl.length}字符 耗时=${latency}ms")
                cb.onResult(Speech(dataUrl, mime, bytes.size, latency), null)
            } catch (e: Throwable) {
                val latency = System.currentTimeMillis() - t0
                val msg = e.message ?: e.javaClass.simpleName
                L2DLog.e(L2DLog.Mod.AI, "TTS 失败", "耗时=${latency}ms err=$msg", e)
                cb.onResult(null, msg)
            }
        }
    }

    // ------------------------------------------------------------------
    // 响应解析（防御式：不同模型系列的响应形状不完全一致）
    // ------------------------------------------------------------------

    /**
     * 依次尝试几种已知形状：
     * - `output.audio.url`（Qwen-TTS 非流式，官方文档形态）
     * - `output.audio.data`（内联 base64，部分系列）
     * - `output.audio_url`
     * - `data.audio.url`
     */
    private fun extractAudioUrl(raw: String): String? {
        val root = JSONObject(raw)
        val out = root.optJSONObject("output")
        val audio = out?.optJSONObject("audio")
        audio?.optString("url")?.takeIf { it.isNotBlank() }?.let { return it }
        audio?.optString("data")?.takeIf { it.isNotBlank() }?.let {
            // 内联 base64：直接拼 data URL 返回，调用方按 http(s) 判断会走不通，
            // 所以这里统一转成 data: 形态返回，download() 会识别并直接解码。
            return "data:audio/wav;base64,$it"
        }
        out?.optString("audio_url")?.takeIf { it.isNotBlank() }?.let { return it }
        root.optJSONObject("data")?.optJSONObject("audio")
            ?.optString("url")?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private fun postJson(url: String, apiKey: String, body: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: ${text.take(300)}")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** @return Pair(字节, mime) */
    private fun download(url: String): Pair<ByteArray, String> {
        // 内联 base64 形态：直接解码
        if (url.startsWith("data:")) {
            val comma = url.indexOf(',')
            if (comma < 0) throw IllegalStateException("data URL 格式不对")
            val head = url.substring(5, comma)              // e.g. audio/wav;base64
            val mime = head.substringBefore(';').ifBlank { "audio/wav" }
            return Base64.decode(url.substring(comma + 1), Base64.DEFAULT) to mime
        }
        // ★ v1.7.17：百炼返回的音频是 OSS 的 **http** 链接，而 Android 9+ 默认禁止明文 HTTP
        //   （实测报 `Cleartext HTTP traffic to dashscope-result-bj.oss-cn-beijing.aliyuncs.com
        //   not permitted`）。OSS 全部支持 HTTPS，所以直接把 scheme 换掉 ——
        //   比在 Manifest 上开 usesCleartextTraffic 或加 network-security-config 更干净：
        //   **不放宽任何安全策略**。
        val safeUrl = if (url.startsWith("http://")) {
            val upgraded = "https://" + url.removePrefix("http://")
            L2DLog.i(L2DLog.Mod.AI, "音频链接已升级为 HTTPS",
                "host=" + java.net.URI(upgraded).host)
            upgraded
        } else url

        val conn = (URL(safeUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("音频下载 HTTP $code")
            val mime = conn.contentType?.substringBefore(';')?.trim().orEmpty()
                .ifBlank { "audio/wav" }
            val bytes = conn.inputStream.use { it.readBytes() }
            return bytes to mime
        } finally {
            conn.disconnect()
        }
    }
}
