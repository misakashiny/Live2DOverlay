package com.live2d.overlay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * v1.7.12：OpenAI-compatible LLM 客户端。
 *
 * ## 为什么放在 Kotlin 侧而不是 WebView
 *
 * 上游 `packages/README.md:1059`、`docs/integration-tutorial.md:527`、
 * `planner-openai/README.md:22-24` **三处明确禁止**把 LLM 长期 API Key 放进浏览器。
 * 所以：Kotlin 持密钥 + 发请求，只把**语义层**结果通过 `evaluateJavascript` 喂给页面。
 *
 * ## 只让 LLM 输出「语义」，不输出参数
 *
 * 调研（`docs/34`）结论：`SpeechPerformancePlan` 的手势模板/曲线/通道幅度
 * **全部由 engine 本地规则 + seededRandom 决定**。LLM 能注入的只有 8 个字段，
 * 且 `semanticCues` / `deliveryHints` 有**硬性白名单**（超出被静默丢弃，
 * `SpeechPerformancePlanner.ts:479-480`）。所以提示词里就把白名单写死，
 * 并在解析时二次过滤 —— 两头都拦。
 *
 * ## 线程模型
 *
 * 用单线程 `Executor` 而不是 `Thread`：并发请求没有意义（会互相抢 VAD），
 * 串行化反而避免乱序覆盖。回调统一切回主线程由调用方处理。
 */
object LlmClient {

    /** 与上游 `SpeechPerformancePlanner.ts:479-480` 保持逐字一致 */
    val SEMANTIC_CUES = listOf(
        "agreement", "reassurance", "question", "reflection", "uncertainty",
        "affection", "emphasis", "contrast", "surprise", "tension", "sadness"
    )
    val DELIVERY_HINTS = listOf(
        "acknowledge", "reassure", "ask", "reflect", "hesitate",
        "confide", "emphasize", "celebrate", "warn"
    )

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "l2d-llm").apply { isDaemon = true }
    }

    /** LLM 返回的语义层（对应页面侧 `SpeechPerformancePlanInput` 的可注入字段） */
    data class Semantic(
        val emotion: String,
        val intensity: Double,
        val confidence: Double,
        val durationMs: Int,
        val semanticCues: List<String>,
        val deliveryHints: List<String>,
        val reply: String,
        val latencyMs: Long,
        val provider: String
    )

    fun interface Callback {
        /** `semantic` 为 null 时看 `error` */
        fun onResult(semantic: Semantic?, error: String?)
    }

    /**
     * 发起一次对话并拿回语义层。
     *
     * @param userMessage 用户说的话
     * @param history     最近若干轮，元素为 `Pair(role, content)`，role ∈ user/assistant
     */
    fun converse(
        context: Context,
        userMessage: String,
        history: List<Pair<String, String>>,
        cb: Callback
    ) {
        val apiKey = SecureKeyStore.apiKey(context)
        if (apiKey.isNullOrBlank()) {
            L2DLog.w(L2DLog.Mod.AI, "LLM 未配置", "缺少 API Key")
            cb.onResult(null, "未配置 API Key")
            return
        }
        val baseUrl = SecureKeyStore.baseUrl(context).trimEnd('/')
        val model = SecureKeyStore.model(context)
        L2DLog.i(L2DLog.Mod.AI, "LLM 请求开始",
            "model=$model baseUrl=$baseUrl key=${SecureKeyStore.masked(apiKey)} " +
                    "history=${history.size} chars=${userMessage.length}")

        io.execute {
            val t0 = System.currentTimeMillis()
            try {
                val body = buildRequest(model, userMessage, history)
                val text = post("$baseUrl/chat/completions", apiKey, body)
                val latency = System.currentTimeMillis() - t0
                val semantic = parse(text, latency, model)
                L2DLog.i(L2DLog.Mod.AI, "LLM 返回",
                    "emotion=${semantic.emotion} intensity=${"%.2f".format(semantic.intensity)} " +
                            "cues=${semantic.semanticCues.joinToString("/")} " +
                            "hints=${semantic.deliveryHints.joinToString("/")} " +
                            "durationMs=${semantic.durationMs} 耗时=${latency}ms " +
                            "回复=${semantic.reply.take(40).replace('\n', ' ')}")
                cb.onResult(semantic, null)
            } catch (t: Throwable) {
                val latency = System.currentTimeMillis() - t0
                val msg = t.message ?: t.javaClass.simpleName
                L2DLog.e(L2DLog.Mod.AI, "LLM 请求失败", "耗时=${latency}ms err=$msg", t)
                cb.onResult(null, msg)
            }
        }
    }

    // ------------------------------------------------------------------
    // 请求构造
    // ------------------------------------------------------------------

    private fun buildRequest(
        model: String,
        userMessage: String,
        history: List<Pair<String, String>>
    ): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        // 只带最近 6 轮，避免无谓的 token 消耗
        history.takeLast(6).forEach { (role, content) ->
            messages.put(JSONObject().put("role", role).put("content", content))
        }
        messages.put(JSONObject().put("role", "user").put("content", userMessage))

        return JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.7)
            .put("max_tokens", 400)
            // ★ 刻意**不传** `response_format`。
            //
            //   上游 planner-openai 是三级降级（json_schema → json_object → 无 format），
            //   但那个字段**不是所有 OpenAI-compatible 服务商都支持**：
            //   OpenAI / DeepSeek / 通义支持；智谱、部分中转站、Ollama 可能直接 400。
            //
            //   而解析侧已有 `extractJson()`（手写括号配平，能处理外面包着 ```json
            //   或解释文字的情况）—— 该字段没有必要，去掉后对所有服务商都安全。
            .toString()
    }

    /**
     * 提示词把白名单写死 —— 与解析侧的二次过滤形成双保险。
     * 明确告诉模型「不要输出参数名」，因为参数由 engine 本地决定。
     */
    private val SYSTEM_PROMPT = """
        你是一个 Live2D 数字角色的对话与情绪规划器。用户会对你说话，你要：
        1. 用中文简短回复（1~2 句，像角色在聊天，不要客套）。
        2. 判断你此刻的情绪与表演方式。

        只输出一个 JSON 对象，字段如下（不要输出任何其他内容）：
        {
          "reply": "你的中文回复",
          "emotion": "情绪名",
          "intensity": 0.0~1.0,
          "confidence": 0.0~1.0,
          "semanticCues": ["从下面列表里选 0~3 个"],
          "deliveryHints": ["从下面列表里选 0~2 个"]
        }

        emotion 只能取以下之一：
          happy, excited, shy, curious, calm, affectionate, surprised,
          concerned, tired, sad, anxiety, anger, confused, neutral

        semanticCues 只能取：${SEMANTIC_CUES.joinToString(", ")}
        deliveryHints 只能取：${DELIVERY_HINTS.joinToString(", ")}

        ★ 不要输出任何 Live2D 参数名或数值 —— 具体动作由本地引擎决定。
        ★ 回复要口语化，符合一个活泼的虚拟歌姬角色。
    """.trimIndent()

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private fun post(url: String, apiKey: String, body: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 45_000
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
                // 只截前 300 字符，避免把整页错误塞进日志
                throw IllegalStateException("HTTP $code: ${text.take(300)}")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------
    // 解析 + 白名单过滤
    // ------------------------------------------------------------------

    private fun parse(raw: String, latencyMs: Long, model: String): Semantic {
        val root = JSONObject(raw)
        val content = root.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
        val obj = JSONObject(extractJson(content))

        val emotion = obj.optString("emotion", "neutral").lowercase()
            .takeIf { it in KNOWN_EMOTIONS } ?: "neutral"
        val intensity = obj.optDouble("intensity", 0.6).coerceIn(0.0, 1.0)
        val confidence = obj.optDouble("confidence", 0.8).coerceIn(0.0, 1.0)
        val cues = filterWhitelist(obj.optJSONArray("semanticCues"), SEMANTIC_CUES)
        val hints = filterWhitelist(obj.optJSONArray("deliveryHints"), DELIVERY_HINTS)
        val reply = obj.optString("reply", "").trim().ifEmpty { "……" }

        // durationMs 由回复长度估算（不依赖上游那个 字数*0.16 的算法）
        val durationMs = estimateDurationMs(reply)

        return Semantic(emotion, intensity, confidence, durationMs, cues, hints, reply, latencyMs, model)
    }

    /**
     * 模型有时会包 ```json 或加解释文字，这里抠出第一个 `{...}` 块。
     * 不用正则跨行匹配 —— 手写括号配平更稳（字符串内的括号也要跳过）。
     */
    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        if (start < 0) throw IllegalStateException("响应里没有 JSON: ${text.take(120)}")
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                esc -> esc = false
                c == '\\' && inStr -> esc = true
                c == '"' -> inStr = !inStr
                !inStr && c == '{' -> depth++
                !inStr && c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        throw IllegalStateException("JSON 括号不配平: ${text.take(120)}")
    }

    private fun filterWhitelist(arr: JSONArray?, allow: List<String>): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optString(i, "").trim()
            // ★ 二次过滤：模型不守规矩时静默丢弃（与上游 normalizeLabels 行为一致）
            if (v.isNotEmpty() && allow.contains(v) && !out.contains(v)) out.add(v)
        }
        return out
    }

    /**
     * 按回复长度估算表演时长。
     * 中文约 5 字/秒，加 600ms 起势与收尾；夹在 1.2~9 秒。
     */
    fun estimateDurationMs(reply: String): Int {
        val chars = reply.count { !it.isWhitespace() }
        val ms = 600 + chars * 200
        return ms.coerceIn(1200, 9000)
    }

    private val KNOWN_EMOTIONS = setOf(
        "happy", "excited", "shy", "curious", "calm", "affectionate", "surprised",
        "concerned", "tired", "sad", "anxiety", "anger", "confused", "neutral"
    )
}
