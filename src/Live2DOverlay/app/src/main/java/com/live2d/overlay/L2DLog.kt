package com.live2d.overlay

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * v1.3.0 统一日志核心。
 *
 * 设计目标：**让迭代排查从「grep logcat」变成「看日志列表」**。
 *
 * 解决的具体痛点：
 *   1. 原先 Kotlin 与 JS 两侧各自 console.log / Log.d，格式不一，无法统一过滤；
 *   2. 只有 logcat 一条通道，App 内看不到、不方便分享给他人；
 *   3. 没有级别概念，debug 噪音与 error 混在一起；
 *   4. 关键链路（加载 → 交互 → 动作 → 参数）缺少可串联的上下文。
 *
 * 因此本类提供四个能力：
 *   · 统一格式：`[L2D][模块][级别] 消息 | k=v k=v`
 *   · 多级过滤：E/W/I/D/V，运行时可调
 *   · 环形缓冲：内存保留最近 [MAX_BUFFER] 条，供界面实时展示
 *   · 落盘 + 导出：写入私有目录，滚动保留 [RETAIN_DAYS] 天，可一键导出到公共目录
 *
 * 线程安全：所有写入走单线程 executor，界面读取走 CopyOnWriteArrayList 快照。
 */
object L2DLog {

    // ------------------------------------------------------------------
    // 常量
    // ------------------------------------------------------------------

    private const val TAG = "L2DLog"

    /** 内存环形缓冲的最大条数。超出后丢弃最旧的。 */
    private const val MAX_BUFFER = 800

    /** 落盘文件保留天数（按天命名，超期自动清理）。 */
    private const val RETAIN_DAYS = 3

    /** 落盘目录名（应用私有目录下）。 */
    private const val LOG_DIR_NAME = "logs"

    // ------------------------------------------------------------------
    // 级别
    // ------------------------------------------------------------------

    /** 日志级别。数值越大越严重，用于「只显示 >= 当前级别」的过滤。 */
    enum class Level(val tag: String, val value: Int) {
        VERBOSE("V", 0),
        DEBUG("D", 1),
        INFO("I", 2),
        WARN("W", 3),
        ERROR("E", 4);

        companion object {
            fun fromTag(tag: String?): Level {
                return when (tag?.uppercase()) {
                    "V", "VERBOSE" -> VERBOSE
                    "D", "DEBUG" -> DEBUG
                    "I", "INFO" -> INFO
                    "W", "WARN" -> WARN
                    "E", "ERROR" -> ERROR
                    else -> DEBUG
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 模块（日志分类）
    // ------------------------------------------------------------------

    /**
     * 模块划分。刻意沿「用户可感知的功能链路」切分，而不是按类名切分 ——
     * 这样排查时可以直接说「看 TOUCH 和 MOTION」，而不是去猜哪个类打了日志。
     */
    object Mod {
        /** 服务生命周期、窗口创建/销毁/重建 */
        const val SVC = "SVC"
        /** 配置读写、URL 构建 */
        const val CFG = "CFG"
        /** JS <-> Kotlin 桥、心跳 */
        const val BRIDGE = "BRIDGE"
        /** 控制台界面 */
        const val UI = "UI"
        /** 模型加载、渲染、上下文 */
        const val RENDER = "RENDER"
        /** 触摸路由、命中判定、拖拽 */
        const val TOUCH = "TOUCH"
        /** 动作/表情播放 */
        const val MOTION = "MOTION"
        /** 参数读写（含水印压制） */
        const val PARAM = "PARAM"
        /** 水印相关 */
        const val WM = "WM"
        /**
         * 点击特效。
         *
         * ★ v1.7.2 补：v1.5.0 文档写「模块表加 FX」，但当时**只加了页面侧的 M.FX**，
         * Kotlin 侧一直缺这一项（页面经桥上报 "FX" 仍能工作，因为 writeFromJs
         * 只做 "JS:" 前缀拼接、不查枚举）。这里补齐，使两侧模块表一致。
         */
        const val FX = "FX"
        /**
         * v1.7.2（AI 角色系统 P1）：人设加载/切换、情绪引擎、LLM 调用与降级。
         *
         * 页面侧 M 常量也必须同步加 —— 否则页面用 M.AI 上报时
         * 界面按模块过滤会漏掉（文档 §02「新增日志模块」的成对要求）。
         */
        const val AI = "AI"
    }

    // ------------------------------------------------------------------
    // 数据模型
    // ------------------------------------------------------------------

    /**
     * 一条日志的结构化表示。
     *
     * [extras] 承载键值对，形如 `hit=498,189,640,548 scale=1.0`，
     * 目的是让关键数值可被检索和肉眼扫描，而不是埋在中文句子里。
     */
    data class Entry(
        val timeMs: Long,
        val level: Level,
        val module: String,
        val message: String,
        val extras: String
    ) {
        fun formatTime(): String = TIME_FMT.format(Date(timeMs))

        /** 完整单行格式，用于界面展示与落盘。 */
        fun formatLine(): String {
            val sb = StringBuilder(96)
            sb.append('[').append(formatTime()).append(']')
                .append('[').append(module).append(']')
                .append('[').append(level.tag).append(']')
                .append(' ').append(message)
            if (extras.isNotEmpty()) {
                sb.append(" | ").append(extras)
            }
            return sb.toString()
        }
    }

    private val TIME_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val FILE_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val STAMP_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** v1.5.0：JSON 导出用的 ISO-8601 时间戳（带毫秒、不带时区偏移，取本地时间）。 */
    private val ISO_FMT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    /** 内存环形缓冲。用固定容量的阻塞队列，写入即出队最旧。 */
    private val buffer = ArrayBlockingQueue<Entry>(MAX_BUFFER)

    /** 界面订阅者。每次写入后回调，便于实时刷新。 */
    private val listeners = CopyOnWriteArrayList<(Entry) -> Unit>()

    /** 是否启用（关闭后所有级别均不输出，用于零开销场景）。 */
    @Volatile
    var enabled: Boolean = true

    /** 当前过滤级别：低于此级别的日志被丢弃。 */
    @Volatile
    /**
     * 当前最低级别；null 表示「全部放行」（界面对应第 0 项）。
     *
     * v1.5.0 改为可空：原来是非空 Level，界面上的「全部」没法表达，
     * 只能退化成 VERBOSE，导致级别持久化后「全部」存不下。
     */
    var minLevel: Level? = Level.DEBUG

    /** 级别的可读标签；null（全部放行）时输出 ALL，避免出现 "null" 字样误导排查。 */
    private fun levelTag(): String = minLevel?.tag ?: "ALL"

    /** 是否同时输出到 logcat（开发期开，正式期关）。 */
    @Volatile
    var mirrorToLogcat: Boolean = true

    /** 是否落盘。 */
    @Volatile
    var persistToFile: Boolean = true

    /** 日志总条数计数器（含被丢弃的），用于界面统计与诊断。 */
    private val totalCount = AtomicInteger(0)

    /** 初始化幂等标志。 */
    private val initialized = AtomicBoolean(false)

    /** 落盘单线程执行器：避免 IO 阻塞调用方（可能来自 UI 线程或 WebView 线程）。 */
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "l2d-log-writer").apply { isDaemon = true }
    }

    private var logDir: File? = null

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    /**
     * 初始化日志系统。应在 Application 或首个组件创建时调用一次。
     *
     * @param context 任意 Context，内部取 applicationContext
     */
    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return

        val app = context.applicationContext
        try {
            val dir = File(app.filesDir, LOG_DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            logDir = dir
            cleanOldLogs(dir)
        } catch (t: Throwable) {
            Log.w(TAG, "init log dir failed", t)
        }

        // 记录会话头，方便区分「同一次运行」
        i(Mod.SVC, "日志系统就绪", "level=${levelTag()} buffer=$MAX_BUFFER retain=${RETAIN_DAYS}d")
    }

    /** 删除超过保留期的日志文件。 */
    private fun cleanOldLogs(dir: File) {
        try {
            val cutoff = System.currentTimeMillis() - RETAIN_DAYS * 24L * 3600_000L
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".log") && f.lastModified() < cutoff) {
                    f.delete()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "clean old logs failed", t)
        }
    }

    // ------------------------------------------------------------------
    // 写入 API
    // ------------------------------------------------------------------

    fun v(module: String, message: String, extras: String = "") =
        write(Level.VERBOSE, module, message, extras)

    fun d(module: String, message: String, extras: String = "") =
        write(Level.DEBUG, module, message, extras)

    fun i(module: String, message: String, extras: String = "") =
        write(Level.INFO, module, message, extras)

    fun w(module: String, message: String, extras: String = "") =
        write(Level.WARN, module, message, extras)

    fun e(module: String, message: String, extras: String = "", tr: Throwable? = null) =
        write(Level.ERROR, module, if (tr != null) "$message (${tr.javaClass.simpleName}: ${tr.message})" else message, extras)

    /**
     * 接收来自 JS 侧的日志（经 Live2DJSBridge.log 转发）。
     *
     * 单独开一个入口而非复用 [d]/[i]，是为了在模块名前统一加 `JS:` 前缀，
     * 这样在日志列表里一眼就能区分「这条来自页面」还是「这条来自原生」。
     */
    fun writeFromJs(level: String, module: String, message: String, extras: String) {
        val lv = Level.fromTag(level)
        write(lv, "JS:$module", message, extras)
    }

    /**
     * 核心写入实现。
     *
     * 顺序：级别过滤 → 构造 Entry → 入缓冲 → 分发 → 落盘。
     * 任何一步失败都不应影响业务，因此全部 try-catch 吞掉。
     */
    private fun write(level: Level, module: String, message: String, extras: String) {
        if (!enabled) return
        val ml = minLevel
        if (ml != null && level.value < ml.value) return

        val entry = try {
            Entry(System.currentTimeMillis(), level, module, message, extras)
        } catch (t: Throwable) {
            return
        }

        totalCount.incrementAndGet()

        // 1) 入内存缓冲：满了就丢最旧的
        try {
            while (!buffer.offer(entry)) {
                buffer.poll()
            }
        } catch (t: Throwable) {
            // ignore
        }

        // 2) 分发到界面
        try {
            listeners.forEach { it(entry) }
        } catch (t: Throwable) {
            // 单个订阅者异常不应影响其他订阅者，但为了简洁这里整体吞掉
        }

        // 3) 输出到 logcat
        if (mirrorToLogcat) {
            try {
                val line = entry.formatLine()
                when (level) {
                    Level.ERROR -> Log.e(TAG, line)
                    Level.WARN -> Log.w(TAG, line)
                    Level.INFO -> Log.i(TAG, line)
                    Level.DEBUG -> Log.d(TAG, line)
                    Level.VERBOSE -> Log.v(TAG, line)
                }
            } catch (t: Throwable) {
            }
        }

        // 4) 落盘（异步）
        if (persistToFile) {
            try {
                ioExecutor.execute { appendToFile(entry) }
            } catch (t: Throwable) {
            }
        }
    }

    /** 追加一行到当天的日志文件。 */
    private fun appendToFile(entry: Entry) {
        val dir = logDir ?: return
        try {
            val name = "l2d-" + FILE_FMT.format(Date(entry.timeMs)) + ".log"
            val file = File(dir, name)
            file.appendText(STAMP_FMT.format(Date(entry.timeMs)) + " " + entry.formatLine() + "\n")
        } catch (t: Throwable) {
            // 落盘失败不打扰业务
        }
    }

    // ------------------------------------------------------------------
    // 读取 API
    // ------------------------------------------------------------------

    /** 取当前缓冲快照（按时间正序）。 */
    fun snapshot(): List<Entry> = buffer.toList().sortedBy { it.timeMs }

    /**
     * 取缓冲快照，可按级别与模块二次过滤。
     *
     * @param minLevel 最低级别，null 表示不限
     * @param moduleKeyword 模块关键字（空表示不限，大小写不敏感）
     * @param textKeyword 消息关键字（空表示不限，大小写不敏感）
     */
    fun query(
        minLevel: Level? = null,
        moduleKeyword: String = "",
        textKeyword: String = ""
    ): List<Entry> {
        val mod = moduleKeyword.trim()
        val text = textKeyword.trim()
        return snapshot().filter { e ->
            if (minLevel != null && e.level.value < minLevel.value) return@filter false
            if (mod.isNotEmpty() && !e.module.contains(mod, ignoreCase = true)) return@filter false
            if (text.isNotEmpty()) {
                val hay = e.message + " " + e.extras
                if (!hay.contains(text, ignoreCase = true)) return@filter false
            }
            true
        }
    }

    fun totalCount(): Int = totalCount.get()

    fun bufferSize(): Int = buffer.size

    fun clear() {
        buffer.clear()
        totalCount.set(0)
        d(Mod.SVC, "日志缓冲已清空")
    }

    /** 注册实时监听。返回反注册函数。 */
    fun addListener(l: (Entry) -> Unit): () -> Unit {
        listeners.add(l)
        return { listeners.remove(l) }
    }

    // ------------------------------------------------------------------
    // 导出
    // ------------------------------------------------------------------

    /**
     * 导出当前内存缓冲 + 最近落盘文件到指定目录。
     *
     * 设计取舍：导出用「内存快照 + 当天文件」两份合并去重，
     * 这样即使进程刚重启（文件有历史、内存为空），导出的仍是完整信息。
     *
     * @return 导出结果描述（成功时为文件绝对路径，失败时为错误说明）
     */
    fun exportTo(targetDir: File): String {
        return try {
            if (!targetDir.exists()) targetDir.mkdirs()
            if (!targetDir.exists()) return "无法创建导出目录：${targetDir.absolutePath}"

            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val out = File(targetDir, "l2d-log-$stamp.txt")

            val sb = StringBuilder(64 * 1024)
            sb.append("===== Live2D Overlay 运行日志 =====\n")
            sb.append("导出时间：").append(STAMP_FMT.format(Date())).append('\n')
            sb.append("缓冲条数：").append(buffer.size).append(" / 累计：").append(totalCount.get()).append('\n')
            sb.append("当前级别：").append(levelTag()).append('\n')
            sb.append("====================================\n\n")

            // 1) 内存缓冲（带完整格式）
            sb.append("----- 内存缓冲（最近 ").append(buffer.size).append(" 条）-----\n")
            snapshot().forEach { sb.append(it.formatLine()).append('\n') }
            sb.append('\n')

            // 2) 当天落盘文件（原始行）
            val dir = logDir
            if (dir != null) {
                val today = File(dir, "l2d-" + FILE_FMT.format(Date()) + ".log")
                if (today.exists()) {
                    sb.append("----- 落盘文件：").append(today.name).append(" -----\n")
                    sb.append(today.readText())
                }
            }

            out.writeText(sb.toString())
            "已导出：${out.absolutePath}"
        } catch (t: Throwable) {
            "导出失败：${t.javaClass.simpleName} ${t.message}"
        }
    }

    /**
     * 导出为结构化 JSON（v1.5.0）。
     *
     * 与 [exportTo] 的分工：纯文本给人读，JSON 给工具读。
     * 后续要把日志喂给分析脚本、或直接贴给助手排查时，JSON 能被直接解析，
     * 不必再写一遍正则去拆行、拆 `k=v`。
     *
     * 结构：
     * ```
     * { "meta": { 导出时间 / 条数 / 级别 ... },
     *   "entries": [ { "t", "ts", "level", "module", "message", "extras", "kv": {...} } ] }
     * ```
     * `kv` 是把 `extras` 里 `k=v k=v` 形式展开成的对象；非该形式时为空对象，
     * 原始字符串始终保留在 `extras` 里，不会因解析失败丢信息。
     *
     * @return 导出结果描述（成功时为文件绝对路径，失败时为错误说明）
     */
    fun exportJsonTo(targetDir: File): String {
        return try {
            if (!targetDir.exists()) targetDir.mkdirs()
            if (!targetDir.exists()) return "无法创建导出目录：${targetDir.absolutePath}"

            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val out = File(targetDir, "l2d-log-$stamp.json")
            val list = snapshot()

            val sb = StringBuilder(64 * 1024)
            sb.append("{\n")
            sb.append("  \"meta\": {\n")
            sb.append("    \"app\": \"Live2DOverlay\",\n")
            sb.append("    \"exportedAt\": \"").append(jsonEscape(ISO_FMT.format(Date()))).append("\",\n")
            sb.append("    \"exportedAtMs\": ").append(System.currentTimeMillis()).append(",\n")
            sb.append("    \"count\": ").append(list.size).append(",\n")
            sb.append("    \"totalCount\": ").append(totalCount.get()).append(",\n")
            sb.append("    \"minLevel\": \"").append(levelTag()).append("\"\n")
            sb.append("  },\n")
            sb.append("  \"entries\": [\n")

            list.forEachIndexed { i, e ->
                sb.append("    {\n")
                sb.append("      \"t\": \"").append(jsonEscape(e.formatTime())).append("\",\n")
                sb.append("      \"ts\": ").append(e.timeMs).append(",\n")
                sb.append("      \"level\": \"").append(e.level.tag).append("\",\n")
                sb.append("      \"module\": \"").append(jsonEscape(e.module)).append("\",\n")
                sb.append("      \"message\": \"").append(jsonEscape(e.message)).append("\",\n")
                sb.append("      \"extras\": \"").append(jsonEscape(e.extras)).append("\",\n")
                sb.append("      \"kv\": {")
                val kv = parseExtras(e.extras)
                sb.append(kv.entries.joinToString(", ") { (k, v) ->
                    "\"" + jsonEscape(k) + "\": \"" + jsonEscape(v) + "\""
                })
                sb.append("}\n")
                sb.append("    }").append(if (i == list.size - 1) "\n" else ",\n")
            }

            sb.append("  ]\n}\n")
            out.writeText(sb.toString())
            "已导出 JSON：${out.absolutePath}"
        } catch (t: Throwable) {
            "导出失败：${t.javaClass.simpleName} ${t.message}"
        }
    }

    /**
     * 把 `k=v k=v` 形式的扩展字段解析成键值对。
     *
     * 只按**第一个**等号切分，这样 value 里再出现 `=`（例如 base64、URL 参数）也不会被截断。
     * 解析不出来就整段跳过 —— extras 本来就是给人看的附注，不该因为格式问题丢日志。
     */
    private fun parseExtras(extras: String): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        if (extras.isBlank()) return map
        extras.trim().split(Regex("\\s+")).forEach { token ->
            val p = token.indexOf('=')
            if (p > 0 && p < token.length - 1) {
                map[token.substring(0, p)] = token.substring(p + 1)
            }
        }
        return map
    }

    /** JSON 字符串转义。只处理必须转义的几个字符，控制字符转成 \u00XX。 */
    private fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch == '\b' -> sb.append("\\b")
                ch == '\u000C' -> sb.append("\\f")
                ch < ' ' -> sb.append("\\u").append(String.format("%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** 打印诊断摘要，用于界面「关于」区域。 */
    fun diagnostics(): String {
        return "条数=${totalCount.get()} 缓冲=${buffer.size}/$MAX_BUFFER " +
               "级别=${levelTag()} 落盘=${if (persistToFile) "开" else "关"} " +
               "目录=${logDir?.absolutePath ?: "未初始化"}"
    }
}
