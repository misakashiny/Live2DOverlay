package com.live2d.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat

/**
 * Live2D 桌面悬浮窗宿主服务。
 *
 * 职责：
 *  1. 通过 WindowManager 添加一个全屏透明、按需可触摸的 TYPE_APPLICATION_OVERLAY 视图；
 *  2. 视图内承载一个硬件加速的透明 WebView，加载 assets 中的 Live2D 渲染页；
 *  3. 通过前台服务保活，并在渲染异常时执行受控重建。
 *
 * 触摸策略（v1.1 起）：
 *
 *  悬浮窗是全屏铺满的，但模型只占其中一小块。若整个窗口都接收触摸，
 *  桌面会被大范围挡住；若整个窗口都不可触摸，模型又无法交互。
 *
 *  因此采用「区域化命中」：由页面实时上报模型在屏幕上的包围盒，
 *  服务侧动态切换窗口的 FLAG_NOT_TOUCHABLE——
 *  · 触摸落在包围盒内 → 清掉 NOT_TOUCHABLE，事件交给 WebView，模型可响应；
 *  · 触摸落在包围盒外 → 加回 NOT_TOUCHABLE，事件穿透到桌面。
 *
 *  切换时机通过重写根 View 的 dispatchTouchEvent 实现：在 ACTION_DOWN 时
 *  先判定命中区域，改完窗口标志后由系统重新派发该次触摸。
 *  这样无需改窗口尺寸，也不必依赖 accessibility 服务。
 *
 * 与原版 MikuCarLauncher 的关键差异：
 *  - 宿主从 Launcher Activity 内嵌 View 改为独立悬浮窗；
 *  - 触摸策略从「整窗屏蔽」升级为「区域命中」；
 *  - 不需要圆角裁切（Live2D 模型 PNG 自带 alpha 通道）。
 */
class OverlayService : Service(), Live2DJSBridge.Listener {

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIF_ID = 0x5201
        private const val CHANNEL_ID = "live2d_overlay_channel"

        /** 渲染心跳超时阈值：超过该时长未收到 ticker 心跳，判定为假死 */
        private const val HEARTBEAT_TIMEOUT_MS = 8000L
        /** 看门狗巡检间隔 */
        private const val WATCHDOG_INTERVAL_MS = 4000L
        /** 单次会话内最多重建次数，防止死循环重建 */
        private const val MAX_REBUILD_PER_SESSION = 5

        /**
         * 命中区域外扩边距（屏幕像素）。
         *
         * 包围盒来自模型的轴对齐矩形，但模型本身是斜的、还有飘动的双马尾，
         * 贴边判定会让用户觉得「明明点到头发却没反应」。外扩一点更符合直觉。
         * 但不能太大，否则会吃掉桌面图标区域的触摸。
         */
        private const val HIT_SLOP_PX = 12

        /** v1.7.2：动画 id 白名单（防止拼接进 JS 时被注入） */
        private val ANIM_ID_OK = Regex("^[A-Za-z0-9_-]{1,64}$")

        const val ACTION_START = "com.live2d.overlay.action.START"
        const val ACTION_STOP = "com.live2d.overlay.action.STOP"
        const val ACTION_REFRESH = "com.live2d.overlay.action.REFRESH"

        /** 触摸交互是否启用（关闭后回到 v1.0 的整窗穿透行为） */
        const val ACTION_SET_TOUCHABLE = "com.live2d.overlay.action.SET_TOUCHABLE"

        /**
         * v1.2.0 调试通道：向页面注入一段 JS。
         *
         * ★ v1.7.1 起**仅 debug 构建可用**（见 onStartCommand 里的 BuildConfig.DEBUG 判定）。
         * 正式功能不要再借道这里 —— 原来三个热切换开关就是这么用的，
         * 结果这条通道无法收口（一收 release 下开关就失效）。
         */
        const val ACTION_DEBUG_JS = "com.live2d.overlay.action.DEBUG_JS"

        /**
         * v1.7.1（迭代清单 P0-4）：页面开关热切换 —— 点击特效 / 边缘吸附 / 动作轮播。
         *
         * 从 ACTION_DEBUG_JS 独立出来的原因：那是「注入任意 JS」的调试口，必须能加
         * debug 守卫；而这三个开关是正式功能，release 下也要能用。本 action 只接受
         * 白名单内的 key，不接受任意 JS 片段。
         */
        const val ACTION_SET_PAGE_OPTION = "com.live2d.overlay.action.SET_PAGE_OPTION"

        /**
         * v1.7.2（AI 角色系统）：播放一个关键帧动画。
         *
         * 单独开这条通道而不是复用 ACTION_DEBUG_JS，原因同 ACTION_SET_PAGE_OPTION ——
         * 调试通道必须在 release 下关闭，而这是正式能力（后续情绪引擎 P2 会大量调用）。
         * id 只允许 [A-Za-z0-9_-]，避免拼进 JS 时被注入。
         */
        const val ACTION_PLAY_ANIMATION = "com.live2d.overlay.action.PLAY_ANIMATION"

        /**
         * v1.7.2：只重新推送人设，**不重载页面**。
         *
         * 为什么不用 ACTION_REFRESH：那会重新加载模型（数秒白屏）。切角色只影响行为，
         * 不需要重建 WebView —— 与 ACTION_SET_ALPHA / ACTION_SET_TOUCHABLE 同样的取舍。
         */
        const val ACTION_REPUSH_PERSONA = "com.live2d.overlay.action.REPUSH_PERSONA"

        /**
         * v1.7.4：向 Soullink 引擎投递一条消息（验收「消息 → 情绪」闭环）。
         *
         * 单独开通道而不是复用调试口 —— 这是正式能力，release 下也要能用
         * （后续接 LLM 时，对话文本就走这条路进引擎）。
         */
        const val ACTION_SOULLINK_MESSAGE = "com.live2d.overlay.action.SOULLINK_MESSAGE"

        /**
         * v1.7.13：播放一次「对话表演」。
         *
         * 与 `ACTION_SOULLINK_MESSAGE` 的区别：
         * - `MESSAGE` 传**文本**，页面侧走 `sendMessage` 文本分类（本地规则）
         * - `PERFORM` 传**语义层 JSON**（emotion/intensity/durationMs/cues/hints），
         *   页面侧走 `SpeechPerformancePlanner.plan()` → `startSpeechPerformance()`
         *
         * 为什么 LLM 的结果走这条：docs/34 调研确认 `SpeechPerformancePlan` 的
         * 手势模板/曲线/通道幅度全由 engine 本地决定，LLM 只该提供语义层。
         */
        const val ACTION_SOULLINK_PERFORM = "com.live2d.overlay.action.SOULLINK_PERFORM"

        /**
         * v1.7.16：播放一段语音（TTS）。
         *
         * 传的是 **data: URL**（不是 http 链接）—— 页面侧用 WebAudio `AnalyserNode`
         * 做 LipSync，而 `createMediaElementSource` 要求音频**同源或带 CORS 头**，
         * 否则输出静音。所以 Kotlin 侧已把音频下载好并转成 data URL。
         */
        const val ACTION_SOULLINK_SPEAK = "com.live2d.overlay.action.SOULLINK_SPEAK"

        /**
         * v1.7.23：调参 —— 实时改引擎增益（bodyMotionGain / parameterGain / motionStyle）。
         *
         * 与其它 action 的区别：它**同时**做两件事 —— 存 prefs（重载后恢复）
         * 与实时投给页面（拖动滑条立刻见效）。
         */
        const val ACTION_SOULLINK_TUNE = "com.live2d.overlay.action.SOULLINK_TUNE"

        /** v1.7.27：显示一条字幕（用于「正在思考…」这类瞬时反馈） */
        const val ACTION_SOULLINK_TOAST = "com.live2d.overlay.action.SOULLINK_TOAST"

        /** v1.7.16：停止语音 */
        const val ACTION_SOULLINK_STOP_SPEAK = "com.live2d.overlay.action.SOULLINK_STOP_SPEAK"

        /** v1.4.0：仅更新窗口透明度，不重载页面（Bug-A 修复配套） */
        const val ACTION_SET_ALPHA = "com.live2d.overlay.action.SET_ALPHA"

        /** v1.5.0：缩放倍率合法区间。下限防止缩到看不见，上限防止大到糊掉。 */
        const val MIN_USER_SCALE = 0.3f
        const val MAX_USER_SCALE = 3.0f

        /** v1.5.0：缩放推送节流间隔（ms）——手指每动一像素就注入一次 JS 会拖住主线程 */
        const val SCALE_PUSH_INTERVAL_MS = 60L

        /**
         * v1.6.0：整体透明度的**真实可达上限**。
         *
         * Android 12+ 对带 FLAG_NOT_TOUCHABLE 的系统悬浮窗强制把 alpha 压到 0.8
         * （系统原话见 makeRenderWindowPassThrough 的注释）。两条突破路径均已实测证伪：
         * 反射 touchableRegion（本机无该成员）、maximum_obscuring_opacity_for_touch=1.0
         * （重启后仍压制）。因此在「全屏穿透式悬浮窗」形态下 0.8 就是天花板。
         *
         * 既然突破不了，就把滑条语义改成**诚实的**：0~100% 线性映射到 0~0.8。
         * 好处有三：
         *   1. 滑到 100% 拿到的就是系统允许的最不透明状态，不会让人误以为还能更高；
         *   2. 全程不再触发系统的压制逻辑，logcat 里不会再有那条告警；
         *   3. 0~80% 区间的滑条手感与视觉变化是线性的，不会出现「80 以上推了没反应」。
         */
        const val MAX_OVERLAY_ALPHA = 0.8f

        /** 把 0~100 的滑条值换算成窗口 alpha（0 ~ MAX_OVERLAY_ALPHA） */
        fun sliderToAlpha(slider: Int): Float =
            MAX_OVERLAY_ALPHA * (slider.coerceIn(0, 100) / 100f)

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var config: OverlayConfig
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootView: View? = null
    private var webView: WebView? = null
    private var bridge: Live2DJSBridge? = null
    private var windowParams: WindowManager.LayoutParams? = null

    // v1.6.0（Bug-017）：捕获窗口的几何信息**不再需要**发布给触摸转发。
    //
    // v1.4.0 曾用 @Volatile 的 catcherOffsetX/Y/ScaleX/Y 解决「闭包捕获旧偏移」，
    // 但那只是半个修复：偏移量本身与「事件生成时刻」之间永远存在竞态
    // （syncTouchCatcher 发布偏移 → updateViewLayout 经 Binder 异步生效）。
    // 现改为直接用 ev.rawX/rawY 取屏幕坐标，这两个字段及其发布逻辑已全部移除。
    // 详见 forwardTouchToWebView() 的注释。

    /** 触摸交互总开关（由设置项控制） */
    private var touchEnabled: Boolean = true

    private var rebuildCount = 0
    private val watchdog = object : Runnable {
        override fun run() {
            checkHealth()
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        L2DLog.init(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        config = OverlayConfig(this)
        // ★ 必须从持久化配置初始化，否则会忽略用户的开关设置：
        // 该字段若保持默认 true，关闭交互后仍会重建触摸捕获窗口，
        // 导致「完全穿透」设置失效。
        touchEnabled = config.touchEnabled
        createNotificationChannel()
        L2DLog.i(L2DLog.Mod.SVC, "服务已创建",
            "touch=${touchEnabled} model=${config.modelPath.substringAfterLast('/')} " +
            "hw=${config.hideWatermark}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfSafely()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                config.enabled = true
                reloadPage()
                return START_STICKY
            }
            ACTION_SET_ALPHA -> {
                // v1.4.0（Bug-A）：透明度只改窗口属性，不重载页面。
                // 重载会白白重新加载一次模型（数秒白屏），对滑条这种连续操作
                // 完全不可接受。
                applyWindowAlpha()
                return START_STICKY
            }
            ACTION_SET_TOUCHABLE -> {
                touchEnabled = intent.getBooleanExtra("enabled", true)
                if (!touchEnabled) {
                    // 关闭：移除捕获窗口，回到全屏纯装饰模式
                    removeTouchCatcher()
                } else {
                    // 开启：按当前包围盒立即重建捕获窗口，无需等待下一次上报
                    val b = bridge
                    if (b != null && b.hitValid) {
                        syncTouchCatcher(b.hitLeft, b.hitTop, b.hitRight, b.hitBottom)
                    }
                }
                pushTouchModeToPage()
                return START_STICKY
            }
            ACTION_DEBUG_JS -> {
                // v1.2.0：调试通道。把 extra 里的 JS 片段注入页面执行。
                //
                // ★ v1.7.1（P0-4）：**release 构建直接忽略**。
                // 这条通道能注入任意 JS（读模型、改参数、发网络请求），不设防就是
                // 发布级安全面。debug 构建保留，便于真机溯源（如水印参数审计）。
                // 三个正式的热切换开关已改走 ACTION_SET_PAGE_OPTION，不受影响。
                if (!BuildConfig.DEBUG) {
                    L2DLog.w(L2DLog.Mod.SVC, "已忽略调试 JS 注入（release 构建）", "action=DEBUG_JS")
                    return START_STICKY
                }
                val js = intent.getStringExtra("js")
                val wv = webView
                if (!js.isNullOrEmpty() && wv != null) {
                    try {
                        wv.evaluateJavascript(js, null)
                    } catch (t: Throwable) {
                        L2DLog.e(L2DLog.Mod.SVC, "调试 JS 注入失败", "", t)
                    }
                }
                return START_STICKY
            }
            ACTION_SET_PAGE_OPTION -> {
                applyPageOption(intent.getStringExtra("key"), intent.getBooleanExtra("on", false))
                return START_STICKY
            }
            ACTION_PLAY_ANIMATION -> {
                // 两种用法：指定具体动画 id，或指定人设里的语义槽位（onTap / onIdle / …）
                val id = intent.getStringExtra("id")
                val slot = intent.getStringExtra("slot")
                when {
                    !id.isNullOrBlank() && ANIM_ID_OK.matches(id) ->
                        evalJsQuiet(
                            "window.__mikuLive2DPlayAnimation && window.__mikuLive2DPlayAnimation('$id')")
                    !slot.isNullOrBlank() && ANIM_ID_OK.matches(slot) ->
                        evalJsQuiet(
                            "window.__mikuLive2DPlayPersonaSlot && window.__mikuLive2DPlayPersonaSlot('$slot')")
                    else -> L2DLog.w(L2DLog.Mod.MOTION, "动画请求非法，已忽略", "id=$id slot=$slot")
                }
                return START_STICKY
            }
            ACTION_REPUSH_PERSONA -> {
                pushPersonaToPage()
                return START_STICKY
            }
            ACTION_SOULLINK_MESSAGE -> {
                val text = intent.getStringExtra("text")
                if (text.isNullOrBlank()) {
                    L2DLog.w(L2DLog.Mod.AI, "Soullink 消息为空，已忽略")
                } else {
                    // 转义后拼进 JS 字符串字面量（消息是用户输入，不能直接拼）
                    val safe = text.replace("\\", "\\\\").replace("'", "\\'")
                        .replace("\n", " ").replace("\r", " ")
                    evalJsQuiet(
                        "window.__mikuLive2DSoullinkMessage && window.__mikuLive2DSoullinkMessage('$safe')")
                    L2DLog.i(L2DLog.Mod.AI, "Soullink 消息已投递", "chars=${text.length} text=$text")
                }
                return START_STICKY
            }
            ACTION_SOULLINK_PERFORM -> {
                // v1.7.13：语义层 JSON 直接进页面，走 planner.plan → startSpeechPerformance。
                // 用 JSONObject.quote() 做 JS 字符串转义 —— 比手写 replace 链更可靠
                // （能正确处理引号、反斜杠、控制字符与 Unicode 行分隔符）。
                val json = intent.getStringExtra("json")
                if (json.isNullOrBlank()) {
                    L2DLog.w(L2DLog.Mod.AI, "对话表演入参为空，已忽略")
                } else {
                    val quoted = org.json.JSONObject.quote(json)
                    evalJsQuiet(
                        "window.__mikuLive2DSoullinkPerform && window.__mikuLive2DSoullinkPerform($quoted)")
                    L2DLog.i(L2DLog.Mod.AI, "对话表演已投递", "bytes=${json.length}")
                }
                return START_STICKY
            }
            ACTION_SOULLINK_TUNE -> {
                val json = intent.getStringExtra("json")
                if (json.isNullOrBlank()) {
                    L2DLog.w(L2DLog.Mod.AI, "调参入参为空，已忽略")
                } else {
                    config.tuneJson = json   // 存起来，重载页面后靠 URL 参数恢复
                    val quoted = org.json.JSONObject.quote(json)
                    evalJsQuiet("window.__mikuLive2DApplyTune && window.__mikuLive2DApplyTune($quoted)")
                    L2DLog.i(L2DLog.Mod.AI, "调参已投递", json)
                }
                return START_STICKY
            }
            ACTION_SOULLINK_TOAST -> {
                val text = intent.getStringExtra("text")
                val ms = intent.getIntExtra("ms", 2500)
                if (!text.isNullOrBlank()) {
                    val q = org.json.JSONObject.quote(text)
                    evalJsQuiet("window.__mikuLive2DShowSubtitle && window.__mikuLive2DShowSubtitle($q, $ms)")
                }
                return START_STICKY
            }
            ACTION_SOULLINK_SPEAK -> {
                // data URL 很长（几百 KB），用 JSONObject.quote() 转义后整串塞进 JS 字面量。
                // Intent extra 上限约 1MB，而 TtsClient 已把 dataUrl 限在 2.4M 字符以内、
                // 实际音频几百 KB，安全。
                val dataUrl = intent.getStringExtra("dataUrl")
                if (dataUrl.isNullOrBlank()) {
                    L2DLog.w(L2DLog.Mod.AI, "语音入参为空，已忽略")
                } else {
                    val quoted = org.json.JSONObject.quote(dataUrl)
                    evalJsQuiet(
                        "window.__mikuLive2DSpeak && window.__mikuLive2DSpeak($quoted)")
                    L2DLog.i(L2DLog.Mod.AI, "语音已投递", "dataUrl=${dataUrl.length}字符")
                }
                return START_STICKY
            }
            ACTION_SOULLINK_STOP_SPEAK -> {
                evalJsQuiet("window.__mikuLive2DStopSpeak && window.__mikuLive2DStopSpeak()")
                L2DLog.i(L2DLog.Mod.AI, "语音停止指令已投递")
                return START_STICKY
            }
            else -> {
                config.enabled = true
                startAsForeground()
                if (!isRunning) {
                    attachOverlay()
                }
            }
        }
        return START_STICKY
    }

    // ------------------------------------------------------------------
    // 前台服务
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startAsForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+：必须显式声明前台服务类型，且清单中已声明 specialUse
                startForeground(
                    NOTIF_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
        } catch (t: Throwable) {
            // 部分 ROM 对前台服务类型校验不一致，降级重试以免整体启动失败
            L2DLog.w(L2DLog.Mod.SVC, "带类型启动前台失败，改用普通方式重试", "err=${t.javaClass.simpleName}")
            try {
                startForeground(NOTIF_ID, buildNotification())
            } catch (t2: Throwable) {
                L2DLog.e(L2DLog.Mod.SVC, "前台服务启动彻底失败", "", t2)
            }
        }
    }

    // ------------------------------------------------------------------
    // 悬浮窗装配
    // ------------------------------------------------------------------

    private fun attachOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            L2DLog.w(L2DLog.Mod.SVC, "未获得悬浮窗权限，放弃挂载")
            stopSelfSafely()
            return
        }
        if (rootView != null) {
            L2DLog.d(L2DLog.Mod.SVC, "悬浮窗已挂载，跳过重复挂载")
            return
        }

        val container = TouchRoutingFrame(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        val wv = buildWebView()
        container.addView(
            wv,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // 渲染窗口**永久不可触摸**：只负责画。触摸由独立的「捕获窗口」承担，
        // 后者尺寸严格等于模型包围盒，其余区域天然属于桌面。
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            baseWindowFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // v1.4.0（Bug-A 修复）：整体透明度此前是「只写不读」的死字段 ——
            // 界面滑条写了 SharedPreferences，但窗口和页面从不消费它，导致
            // 100% 与 30% 视觉上完全一致。这里直接在窗口层级生效：窗口 alpha
            // 作用于整棵 View 树，无需页面配合，也不与模型自身的 alpha 冲突。
            //
            // v1.6.0：改用 sliderToAlpha()，把 0~100 映射到 0~0.8（真实可达上限）。
            alpha = sliderToAlpha(config.overlayAlpha)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // 见 makeRenderWindowPassThrough 的注释：优先用「空触摸区域」替代
        // FLAG_NOT_TOUCHABLE，以绕开 Android 12+ 强加的 0.8 不透明上限。
        makeRenderWindowPassThrough(params)

        try {
            windowManager.addView(container, params)
        } catch (t: Throwable) {
            L2DLog.e(L2DLog.Mod.SVC, "添加渲染窗口失败", "", t)
            destroyWebView()
            stopSelfSafely()
            return
        }

        rootView = container
        windowParams = params
        webView = wv
        isRunning = true
        rebuildCount = 0
        loadPage(wv)
        mainHandler.removeCallbacks(watchdog)
        mainHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
        L2DLog.i(L2DLog.Mod.SVC, "悬浮窗已挂载", "mode=两窗口分离")
    }

    /**
     * v1.4.0（Bug-A 修复）：把「整体透明度」应用到渲染窗口。
     *
     * 为什么用窗口级 alpha 而非页面级：
     *  窗口 alpha 作用于整棵 View 树（含 WebView 合成结果），一处生效全局可见，
     *  且不依赖页面配合；页面级方案需要 JS 逐帧改容器 CSS，既多一次桥接往返，
     *  又容易与模型自身 alpha 叠加出现双重衰减。
     *
     * 用 updateViewLayout 而非重建窗口：透明度变化不需要重新挂载，
     *  重建会导致闪烁和 WebView 重新加载。
     */
    private fun applyWindowAlpha() {
        val container = rootView ?: return
        val params = windowParams ?: return
        val a = sliderToAlpha(config.overlayAlpha)
        if (params.alpha == a) return
        params.alpha = a
        try {
            windowManager.updateViewLayout(container, params)
            L2DLog.d(L2DLog.Mod.UI, "窗口透明度已更新", "alpha=$a")
        } catch (t: Throwable) {
            L2DLog.e(L2DLog.Mod.UI, "更新窗口透明度失败", "", t)
        }
    }

    // ==================================================================
    // 触摸捕获窗口（独立于渲染窗口）
    //
    // 为什么需要第二个窗口：
    //
    // 渲染窗口是全屏的，若让它可触摸，桌面几乎全部区域都会被它吃掉；
    // 若让它 NOT_TOUCHABLE，View 树收不到事件，又无法判断落点是否在模型上
    // ——「按下后再翻转标志」的做法已被实测证伪：Android 的输入管线在
    // ACTION_DOWN 派发时就把整个手势序列绑定给了目标窗口，事后摘除
    // FLAG_NOT_TOUCHABLE 不会让已注入的 DOWN 重新路由到下层。
    //
    // 因此改为：渲染窗口**永久 NOT_TOUCHABLE**，另建一个尺寸/位置恰好等于
    // 模型包围盒的小窗口专门接收触摸。模型之外的区域从来就不属于任何窗口，
    // 触摸天然落到桌面，无需任何时序博弈。
    // ==================================================================

    private var touchCatcher: View? = null

    /** 触摸捕获窗口的容器：承载一个与渲染层共用桥接的轻量 WebView 代理 */
    private fun buildTouchCatcher(): View {
        return android.view.View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = false
        }
    }

    /**
     * 按模型包围盒同步触摸捕获窗口的位置与尺寸。
     *
     * 输入的包围盒是 CSS 像素，需要换算到屏幕像素。
     *
     * @param left   CSS 像素
     * @param top    CSS 像素
     * @param right  CSS 像素
     * @param bottom CSS 像素
     */
    private fun syncTouchCatcher(left: Float, top: Float, right: Float, bottom: Float) {
        if (!touchEnabled) {
            removeTouchCatcher()
            return
        }

        val b = bridge ?: return
        val vw = b.viewportW
        val vh = b.viewportH
        if (vw <= 0f || vh <= 0f) return

        // 取屏幕实际尺寸作为换算基准（View 尺寸可能与屏幕不同，这里用显示指标）
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels.toFloat()
        val screenH = dm.heightPixels.toFloat()

        val scaleX = screenW / vw
        val scaleY = screenH / vh

        // 外扩边距（屏幕像素），与命中判定保持一致的手感
        val slop = HIT_SLOP_PX

        var x = (left * scaleX - slop).toInt()
        var y = (top * scaleY - slop).toInt()
        var w = ((right - left) * scaleX + slop * 2).toInt()
        var h = ((bottom - top) * scaleY + slop * 2).toInt()

        // 夹到屏幕内，避免越界导致 addView 失败
        x = x.coerceIn(0, (screenW.toInt() - 1))
        y = y.coerceIn(0, (screenH.toInt() - 1))
        w = w.coerceAtLeast(1).coerceAtMost(screenW.toInt() - x)
        h = h.coerceAtLeast(1).coerceAtMost(screenH.toInt() - y)

        // v1.6.0（Bug-017）：这里不再发布窗口偏移与换算比例。
        // 触摸转发改用 ev.rawX/rawY（屏幕坐标），与窗口位置解耦，
        // 从根上消除「新偏移 + 旧局部坐标」的竞态（拖动时模型跳动的根因）。

        val params = WindowManager.LayoutParams(
            w,
            h,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // 可触摸：唯一职责就是接收触摸并转发给主 WebView。
            // 仍保留 NOT_FOCUSABLE，避免抢占输入焦点。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

        // 事件转发：把捕获窗口上的触摸坐标平移后投递给渲染层 WebView 处理。
        // 这样页面里的 pointerdown 等事件仍以 WebView 坐标系为准，页面逻辑无需改动。
        val view = touchCatcher
        if (view == null) {
            val created = buildTouchCatcher()
            val forwarding = android.view.GestureDetector(
                this,
                object : android.view.GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: android.view.MotionEvent): Boolean = true
                }
            )
            // v1.4.0（Bug-B2 修复）：坐标偏移量必须**动态读取**，不能在 lambda 里
            // 闭包捕获 syncTouchCatcher 的入参。原因：捕获窗口在拖拽中会不断移动，
            // 而闭包捕获的是「窗口创建那一刻」的 x/y —— 之后每次拖动，转发给
            // WebView 的偏移量都停留在旧值，页面收到的坐标持续偏移，
            // 表现为「模型乱闪 / 瞬移」；偏移累积几次后命中判定彻底失效，
            // 表现为「拖几次就拖不动了」。
            created.setOnTouchListener { _, ev ->
                forwarding.onTouchEvent(ev)
                // v1.5.0：双指缩放。识别必须在原生侧做 —— 转发给 WebView 的是
                // 单点合成事件，页面永远收不到第二根手指，无法自行识别 pinch。
                if (handlePinch(ev)) return@setOnTouchListener true
                // v1.6.0（Bug-017）：不再传窗口偏移，改用 ev.rawX/rawY 取屏幕坐标，
                // 详见 forwardTouchToWebView 的注释。
                forwardTouchToWebView(ev)
                true
            }
            try {
                windowManager.addView(created, params)
                touchCatcher = created
                L2DLog.i(L2DLog.Mod.TOUCH, "捕获窗口已创建", "x=$x y=$y w=$w h=$h")
            } catch (t: Throwable) {
                L2DLog.e(L2DLog.Mod.TOUCH, "添加捕获窗口失败", "", t)
            }
            return
        }

        try {
            windowManager.updateViewLayout(view, params)
            L2DLog.v(L2DLog.Mod.TOUCH, "捕获窗口已移动", "x=$x y=$y w=$w h=$h")
        } catch (t: Throwable) {
            L2DLog.w(L2DLog.Mod.TOUCH, "更新捕获窗口布局失败", "err=${t.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------
    // v1.5.0：双指缩放
    // ------------------------------------------------------------------

    private var pinchActive = false
    private var pinchBaseDist = 0f
    private var pinchBaseScale = 1f
    private var lastScalePushAt = 0L

    /**
     * v1.7.1：双指手势收尾后，紧接着到来的那个 ACTION_UP 要吃掉。
     *
     * 双指抬起的事件序列是 POINTER_UP(2) → UP(1)。POINTER_UP 里已把 pinchActive
     * 归位，于是后面的 UP 会落到转发路径，被页面当成一次「点击」——白播一个动作
     * 外加一次点击特效。用这个标志把它拦掉。
     */
    private var suppressNextUp = false

    /** 取两个手指的当前距离；不足两指或取值异常时返回 0 */
    private fun pointerDistance(ev: android.view.MotionEvent): Float {
        if (ev.pointerCount < 2) return 0f
        return try {
            val dx = ev.getX(0) - ev.getX(1)
            val dy = ev.getY(0) - ev.getY(1)
            Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        } catch (t: Throwable) {
            0f
        }
    }

    /**
     * 双指缩放手势识别。
     *
     * @return true 表示事件已被缩放手势消费，不应再转发给页面。
     *
     * 两个容易漏掉的点：
     *  1. 第二根手指落下时必须先撤销页面里可能已经开始的单指拖拽，
     *     否则页面一边跟着手指改 model.x、一边被缩放，观感是「放大同时乱飘」；
     *  2. 手势结束时必须把 pinchActive 归位，否则下一轮单指操作会被误判成缩放，
     *     表现为「缩放一次之后拖动就失灵了」。
     */
    private fun handlePinch(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount >= 2 && !pinchActive) {
                    val d = pointerDistance(ev)
                    if (d > 0f) {
                        pinchActive = true
                        pinchBaseDist = d
                        pinchBaseScale = config.scale
                        evalJsQuiet("window.__mikuLive2DCancelPointer && window.__mikuLive2DCancelPointer()")
                        L2DLog.i(L2DLog.Mod.TOUCH, "双指缩放开始",
                            "baseScale=" + String.format("%.2f", pinchBaseScale))
                    }
                }
                // ★ 始终消费 POINTER_DOWN：多指事件对「只收单点」的页面毫无意义，
                // 放行到转发路径还会因单点重载构造出 pointerIndex 越界的事件而崩溃。
                return true
            }

            android.view.MotionEvent.ACTION_MOVE -> {
                if (!pinchActive) return false
                // 手指数量掉回 1（中间抬起）时静默等待，不推送也不结束
                val d = pointerDistance(ev)
                if (d <= 0f || pinchBaseDist <= 0f) return true
                applyUserScale(pinchBaseScale * (d / pinchBaseDist))
                return true
            }

            android.view.MotionEvent.ACTION_POINTER_UP -> {
                // ACTION_POINTER_UP 时 pointerCount 仍包含刚抬起的那根手指，
                // 因此「<= 2」即表示两指中走了一根。
                if (pinchActive && ev.pointerCount <= 2) {
                    pinchActive = false
                    // 强制补推一次：节流可能把最后一小段位移丢掉，
                    // 不补就会出现「手指停下的位置和最终大小差一点」。
                    applyUserScale(config.scale, force = true)
                    L2DLog.i(L2DLog.Mod.TOUCH, "双指缩放结束",
                        "scale=" + String.format("%.2f", config.scale))
                }
                // ★ 始终消费 POINTER_UP（原因同 POINTER_DOWN），并标记吃掉紧随其后的 UP。
                suppressNextUp = true
                return true
            }

            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                // 双指手势的收尾 UP：吃掉，否则页面会当成一次点击（白播动作 + 特效）
                if (suppressNextUp) {
                    suppressNextUp = false
                    pinchActive = false
                    return true
                }
                if (pinchActive) {
                    pinchActive = false
                    applyUserScale(config.scale, force = true)
                    L2DLog.i(L2DLog.Mod.TOUCH, "双指缩放结束",
                        "scale=" + String.format("%.2f", config.scale))
                    return true   // 消费掉：否则页面会把这次抬起当成一次点击
                }
                return false
            }
        }
        return pinchActive
    }

    /**
     * 落地缩放值：持久化 + 推给页面重排（默认节流）。
     *
     * @param force true 时跳过节流（用于手势结束的收尾推送）
     */
    private fun applyUserScale(raw: Float, force: Boolean = false) {
        val v = raw.coerceIn(MIN_USER_SCALE, MAX_USER_SCALE)
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - lastScalePushAt < SCALE_PUSH_INTERVAL_MS) return
        lastScalePushAt = now
        if (!force && Math.abs(v - config.scale) < 0.005f) return   // 变化太小不打扰页面

        config.scale = v
        evalJsQuiet("window.__mikuLive2DSetUserScale && window.__mikuLive2DSetUserScale($v)")
        L2DLog.d(L2DLog.Mod.TOUCH, "缩放推送", "scale=" + String.format("%.2f", v))
    }

    /** 向页面注入一段 JS。失败只记日志，不打断手势。 */
    private fun evalJsQuiet(js: String) {
        try {
            webView?.evaluateJavascript(js, null)
        } catch (t: Throwable) {
            L2DLog.w(L2DLog.Mod.TOUCH, "JS 注入失败", "err=${t.javaClass.simpleName}")
        }
    }

    /**
     * 把捕获窗口收到的事件投递到渲染层 WebView。
     *
     * ★ 坐标必须用 `ev.rawX / rawY`（屏幕坐标），**不能**用 `ev.x + 窗口偏移`。
     *
     * 为什么（Bug-017，拖动时模型闪现跳动的真正根因）：
     *   `ev.x` 是「事件生成那一刻」相对捕获窗口的局部坐标，而窗口偏移是
     *   `syncTouchCatcher()` 刚发布的值 —— 但 `updateViewLayout()` 要经 Binder
     *   异步生效。于是窗口移动后的那几帧里，「新偏移 + 旧局部坐标」算出的屏幕坐标
     *   会整体偏掉一个位移量。
     *
     *   拖动时捕获窗口每帧都跟着模型移动，于是形成自激振荡：
     *   窗口移动 → 坐标偏掉 → 页面按增量又把模型挪一次 → 命中区再移动 → 再偏掉…
     *   页面侧 `model.x += x - pointerLastX` 是**增量**累加，所以每一次偏差都会
     *   永久留在模型位置上 → 表现为「拖动时到处闪 / 跳动」。
     *
     *   `rawX / rawY` 由输入系统按显示坐标给出，与窗口位置、与我们的记账**都无关**，
     *   从根上消除这类误差。（v1.4.0 的 Bug-013 只修了「闭包捕获旧偏移」那一半，
     *   另一半就是这里。）
     *
     * WebView 铺满全屏且位于 (0,0)，其 View 坐标系即屏幕坐标系，
     * 故可直接把屏幕坐标当作 WebView 局部坐标投递。
     */
    private fun forwardTouchToWebView(ev: android.view.MotionEvent) {
        val wv = webView ?: return

        // ★★ 只能转发「单点」事件，且绝不能转发 POINTER_DOWN / POINTER_UP。
        //
        // 下面用的是单点重载 MotionEvent.obtain(downTime, eventTime, action, x, y, metaState)，
        // 它构造出的 pointerCount 恒为 1；而 ACTION_POINTER_DOWN / ACTION_POINTER_UP 的
        // action 里**编码了 pointerIndex**（如 POINTER_UP(1) 表示「下标 1 的手指抬起」）。
        // 一旦把这种 action 交给 ViewGroup.dispatchTouchEvent，它会去调
        // getPointerId(1) —— 而事件里只有 1 个指针 → 越界 → 崩溃。
        //
        // 真机崩溃证据（dropbox，v1.7.0，用户双指缩小到下限时连续触发两次）：
        //   java.lang.IllegalArgumentException: invalid pointerIndex 1 for MotionEvent
        //     { action=POINTER_UP(1), id[0]=0, ... }
        //       at MotionEvent.getPointerId
        //       at ViewGroup.dispatchTouchEvent
        //       at OverlayService.forwardTouchToWebView(OverlayService.kt:681)
        //   另一次同源 native crash：SIGABRT /
        //   "JNI DETECTED ERROR: JNI CallVoidMethodV called with pending exception"
        //
        // 页面本来就只能理解单点事件（原生只转发单点），所以直接丢弃是正确行为。
        if (ev.pointerCount != 1) return
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_POINTER_DOWN,
            android.view.MotionEvent.ACTION_POINTER_UP -> return
        }

        val converted = android.view.MotionEvent.obtain(
            ev.downTime,
            ev.eventTime,
            ev.action,
            ev.rawX,
            ev.rawY,
            ev.metaState
        )
        wv.dispatchTouchEvent(converted)
        converted.recycle()
    }

    private fun removeTouchCatcher() {
        touchCatcher?.let { v ->
            try {
                windowManager.removeView(v)
            } catch (t: Throwable) {
                L2DLog.w(L2DLog.Mod.TOUCH, "移除捕获窗口失败", "err=${t.javaClass.simpleName}")
            }
        }
        touchCatcher = null
    }

    /**
     * 窗口基础标志位。
     *
     * NOT_TOUCHABLE 在基础标志与可触摸标志之间切换，其余保持不变：
     *  - NOT_FOCUSABLE        不抢输入焦点（否则会顶掉输入法/返回键）
     *  - LAYOUT_IN_SCREEN     坐标系对齐整个屏幕，不受状态栏裁切影响
     *  - LAYOUT_NO_LIMITS     允许覆盖到刘海/挖孔区域
     *  - HARDWARE_ACCELERATED WebGL 必需
     */
    private fun baseWindowFlags(): Int {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }

    private fun windowFlags(touchable: Boolean): Int =
        if (touchable) baseWindowFlags()
        else baseWindowFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

    /**
     * v1.6.0：让渲染窗口「既不接收触摸、又不受 0.8 不透明上限约束」。
     *
     * 背景（真机实测，小米平板 Android 13 / SDK 33）：
     * Android 12 起，系统对带 FLAG_NOT_TOUCHABLE 的**系统悬浮窗**强制压低不透明度。
     * logcat 里 WindowManager 的原话是：
     *
     *   App com.live2d.overlay has a system alert window (type = 2038) with
     *   FLAG_NOT_TOUCHABLE and LayoutParams.alpha = 1.00 > 0.80, setting alpha to 0.80
     *   to let touches pass through (if this is isn't desirable, remove flag
     *   FLAG_NOT_TOUCHABLE).
     *
     * dumpsys 里也能直接看到 `ty=APPLICATION_OVERLAY fmt=TRANSLUCENT alpha=0.8`，
     * 而应用写入的是 1.0。这就是「整体透明度拉到 100% 仍然有点透明」的真正原因，
     * 与模型自身 alpha 无关。
     *
     * 解法就用系统自己给的建议：**去掉 FLAG_NOT_TOUCHABLE**，改用
     * 「可触摸窗口 + 空触摸区域」达到等效的穿透效果 —— 窗口仍然收不到任何触摸，
     * 但它不再是「不可触摸窗口」，因此不受 0.8 上限约束。
     *
     * setTouchableRegion 是隐藏 API（公开 SDK 里没有），故走反射；
     * 反射失败时退回 FLAG_NOT_TOUCHABLE —— 功能完全不受影响，只是仍有 0.8 上限。
     *
     * @return true = 已走新路径（可达到真正 100% 不透明）
     */
    private fun makeRenderWindowPassThrough(params: WindowManager.LayoutParams): Boolean {
        // ── 已实测：应用层无法突破 0.8 不透明上限，故直接使用 FLAG_NOT_TOUCHABLE ──
        //
        // 曾尝试用「可触摸窗口 + 空触摸区域」绕开限制（系统日志自己给的建议），
        // 但真机枚举 WindowManager.LayoutParams 的全部触摸相关成员后确认：
        //   fields  = [FLAG_NOT_TOUCHABLE, FLAG_NOT_TOUCH_MODAL, FLAG_SPLIT_TOUCH,
        //              FLAG_TOUCHABLE_WHEN_WAKING, FLAG_WATCH_OUTSIDE_TOUCH]
        //   methods = []
        // 即 Android 13 上**不存在** touchableRegion 字段，也没有 setTouchableRegion 方法
        // （隐藏的也没有）→ 应用无法指定窗口触摸区域。
        // 因此一旦去掉 FLAG_NOT_TOUCHABLE，全屏窗口就会吃掉整个桌面的触摸，方案不成立。
        //
        // 同时实测：`settings put secure maximum_obscuring_opacity_for_touch 1.0`
        // **重启后仍然压制到 0.8**（该设置在本机 MIUI/HyperOS 上不生效）。
        //
        // 结论：在「全屏穿透式悬浮窗」这个形态下，80% 是本机的硬上限。
        // 若必须 100%，只能改用「把模型渲染进可触摸的包围盒窗口」——
        // 代价是模型包围盒矩形内的触摸不再穿透到桌面。详见 docs/01_迭代清单.md。
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return false
    }

    /**
     * 渲染容器。
     *
     * 触摸路由已外移到独立的捕获窗口（见 syncTouchCatcher），
     * 本容器保持透明且不参与交互，仅作为 WebView 的宿主。
     */
    private inner class TouchRoutingFrame(context: Context) :
        android.widget.FrameLayout(context)

    /**
     * v1.7.1（P0-4）：应用一个页面开关。
     *
     * 只认白名单 key，不接受任意 JS —— 这是它与 ACTION_DEBUG_JS 的本质区别。
     * 页面侧对应的接口见 live2d_decor.html 的 window.__mikuLive2D* 暴露表。
     */
    private fun applyPageOption(key: String?, on: Boolean) {
        val v = if (on) 1 else 0
        when (key) {
            "cycle" -> evalJsQuiet(
                "window.__mikuLive2DSetActionCycle && window.__mikuLive2DSetActionCycle($v)")
            "fx", "snap" -> evalJsQuiet(
                "window.__mikuLive2DSetOption && window.__mikuLive2DSetOption('$key', $v)")
            else -> L2DLog.w(L2DLog.Mod.SVC, "未知的页面开关，已忽略", "key=$key")
        }
    }

    /** 通知页面当前触摸模式，页面据此决定是否渲染交互反馈 */
    private fun pushTouchModeToPage() {
        val wv = webView ?: return
        val v = if (touchEnabled) "1" else "0"
        wv.post {
            try {
                wv.evaluateJavascript("window.__mikuLive2DSetInteractive && window.__mikuLive2DSetInteractive($v);", null)
            } catch (t: Throwable) {
                L2DLog.w(L2DLog.Mod.TOUCH, "推送触摸模式到页面失败", "err=${t.javaClass.simpleName}")
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        val wv = WebView(this)
        wv.setBackgroundColor(Color.TRANSPARENT)
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        // 交互开启后必须可点击，否则 WebView 会吞掉事件但不派发给页面脚本
        wv.isClickable = true
        wv.isFocusable = false
        wv.isLongClickable = false
        // 移动端多点触摸，交给页面自己处理手势
        wv.isHapticFeedbackEnabled = false

        val b = Live2DJSBridge(this)
        bridge = b
        wv.addJavascriptInterface(b, Live2DJSBridge.JS_INTERFACE_NAME)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 允许 file:// 页面访问同目录下的其它 file:// 资源（离线运行库）
            allowFileAccess = true
            allowContentAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            // 硬件加速下 WebGL 才能启用
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                L2DLog.d(L2DLog.Mod.RENDER, "页面加载完成", "url=$url")
                pushTouchModeToPage()
                // v1.6.0（P2-3）：页面脚本已就绪，注入模型档案覆盖内置动作库。
                // 必须在 onPageFinished 之后 —— 页面的 __mikuLive2DApplyProfile
                // 定义在内联 <script> 里，此时已可用。
                pushModelProfileToPage()
                // v1.7.2：人设必须在档案之后推送（人设的动画槽位引用档案里的动画 id）
                pushPersonaToPage()
                // v1.7.3：Soullink 引擎（未启用时这一步是空操作）
                pushSoullinkToPage()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                L2DLog.w(L2DLog.Mod.RENDER, "Web 资源加载错误", "desc=${error?.description} url=${request?.url}")
                // 主文档加载失败才重建；子资源（如模型文件）缺失由 JS 层容错
                if (request?.isForMainFrame == true) {
                    onRenderError("main-frame-load-failed")
                }
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? = null
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                val raw = cm?.message() ?: return true
                // v1.3.0：页面的结构化日志以 "L2D[" 开头（见 live2d_decor.html 的 LOG 核心）。
                // 优先走 [log] 桥接接口，那条路径更完整；这里只作为兜底，
                // 保证即使桥未就绪（例如加载早期）日志也不会丢。
                if (raw.startsWith("L2D[")) {
                    L2DLog.d(L2DLog.Mod.BRIDGE, "页面日志(兜底通道)",
                        raw.removePrefix("L2D"))
                } else {
                    L2DLog.v(L2DLog.Mod.BRIDGE, "页面原生输出",
                        "msg=$raw line=${cm?.lineNumber()}")
                }
                return true
            }
        }

        return wv
    }

    // ------------------------------------------------------------------
    // v1.6.0（迭代清单 P2-3）：模型档案外置
    // ------------------------------------------------------------------

    /**
     * 把 model-profile.json 注入页面，覆盖页面内置的动作库。
     *
     * 加载优先级：
     *   1. `<模型目录>/model-profile.json` —— 随模型走，放 /sdcard 上可热改
     *   2. `assets/live2d/model-profile.json` —— 内置兜底
     *   3. 都读不到 → **不注入**，页面沿用内置 MODEL_ACTIONS（短按仍可用）
     *
     * 为什么由原生读文件再注入，而不是让页面自己 fetch：
     *   页面以 `file:///android_asset/` 加载，对同目录 JSON 发 fetch/XHR 会受
     *   file:// 同源策略限制，各 ROM 行为不一致。原生读文件最稳，而且顺带支持
     *   「每个模型目录各自一份档案」——这比单一全局档案更符合换模型免改代码的目标。
     */
    private fun pushModelProfileToPage() {
        val wv = webView ?: return
        val json = loadModelProfileJson() ?: return
        // 直接以 JSON 字面量作为实参。档案由我们自己生成或用户手写，
        // 页面侧 __mikuLive2DApplyProfile 会做结构校验，非法时保持内置兜底。
        wv.evaluateJavascript(
            "window.__mikuLive2DApplyProfile && window.__mikuLive2DApplyProfile($json)",
            null
        )
    }

    /**
     * v1.7.2（AI 角色系统 P1）：把当前人设推给页面。
     *
     * ★ 必须在模型档案**之后**推送 —— 人设里的 `animations` 槽位引用的是
     *   model-profile.json 里的动画 id，档案先落地页面才知道有哪些动画可用。
     *
     * 人设加载失败不影响可用性：页面保持内置行为（点击走原 actions 路径）。
     */
    private fun pushPersonaToPage() {
        val wv = webView ?: return
        val id = config.activePersonaId
        val p = PersonaStore.load(this, id)
        if (p == null) {
            L2DLog.w(L2DLog.Mod.AI, "人设加载失败，页面沿用内置行为", "id=$id")
            return
        }
        // 直接把原始 JSON 文本当实参：JSON 对象字面量是合法 JS 表达式
        wv.evaluateJavascript(
            "window.__mikuLive2DApplyPersona && window.__mikuLive2DApplyPersona(${p.rawJson})",
            null
        )
        L2DLog.i(L2DLog.Mod.AI, "人设已推送",
            "id=${p.id} name=${p.name} traits=${p.traits.size} bias=${p.emotionBias.size}")
    }

    /**
     * v1.7.3（实验）：把 Soullink 模型档案注入页面并启动引擎。
     *
     * 复用「原生读文件 + 注入」方案的原因与上面完全相同：engine 的
     * `loadModelProfile()` 内部用 `fetch()`，在 `file://` 下报 unknown scheme
     * （真机已验证）—— 与本项目 v1.7.0 踩过的是同一个坑。
     *
     * **未启用时直接返回** —— 页面不会去加载那 133 KB 的引擎脚本，零开销。
     */
    private fun pushSoullinkToPage() {
        if (!config.soullinkEnabled) {
            L2DLog.i(L2DLog.Mod.AI, "Soullink 未启用，跳过（内置 idle + 关键帧动画接管）")
            return
        }
        val wv = webView ?: return

        // v1.7.5：优先读 sdcard 覆盖版 —— 这样调 motionStyle / 幅度只需 push 一个 JSON，
        // 不用重新构建装机（与 model-profile.json 的加载策略一致）。
        val json = loadSoullinkProfileText() ?: return

        // 结构校验：没有 parameterMap 的话引擎起来也是空转
        val mapSize = try {
            org.json.JSONObject(json).optJSONObject("parameterMap")?.length() ?: 0
        } catch (t: Throwable) {
            0
        }
        if (mapSize <= 0) {
            L2DLog.w(L2DLog.Mod.AI, "Soullink 档案无效（无 parameterMap），引擎未启动")
            return
        }

        wv.evaluateJavascript(
            "window.__mikuLive2DStartSoullink && window.__mikuLive2DStartSoullink($json)",
            null
        )
        L2DLog.i(L2DLog.Mod.AI, "Soullink 档案已注入",
            "bytes=${json.length} parameterMap=$mapSize")
    }

    /** 依次尝试「模型目录旁的档案」→「assets 内置档案」；都读不到返回 null */
    private fun loadModelProfileJson(): String? {
        // 1) 模型目录旁的档案（随模型走，放 /sdcard 上可热改）
        try {
            val mp = config.modelPath
            if (mp.startsWith("file://")) {
                val modelFile = java.io.File(android.net.Uri.parse(mp).path ?: "")
                val f = java.io.File(modelFile.parentFile, "model-profile.json")
                if (f.isFile) {
                    val text = f.readText()
                    if (isValidProfile(text)) {
                        L2DLog.i(L2DLog.Mod.MOTION, "使用模型目录内的档案",
                            "path=${f.absolutePath} bytes=${text.length}")
                        return text
                    }
                    L2DLog.w(L2DLog.Mod.MOTION, "模型目录内的档案无效，回退内置档案",
                        "path=${f.absolutePath} bytes=${text.length}")
                }
            }
        } catch (t: Throwable) {
            L2DLog.w(L2DLog.Mod.MOTION, "读取模型目录档案失败", "err=${t.javaClass.simpleName}")
        }
        // 2) assets 内置档案
        return try {
            val text = assets.open("live2d/model-profile.json")
                .bufferedReader().use { it.readText() }
            if (isValidProfile(text)) {
                L2DLog.i(L2DLog.Mod.MOTION, "使用内置模型档案", "bytes=${text.length}")
                text
            } else {
                L2DLog.w(L2DLog.Mod.MOTION, "内置模型档案无效，沿用页面内置动作库",
                    "bytes=${text.length}")
                null
            }
        } catch (t: Throwable) {
            L2DLog.w(L2DLog.Mod.MOTION, "内置模型档案缺失，沿用页面内置动作库",
                "err=${t.javaClass.simpleName}")
            null
        }
    }

    /**
     * v1.7.5：读取 Soullink 档案文本。
     *
     * 优先级：`/sdcard/Live2DModels/<模型目录>/soullink.profile.json`
     *      → `assets/live2d/soullink.profile.json`
     *
     * 为什么优先 sdcard：调 `motionStyle` / 动作幅度时只需 push 一个 JSON 再重开悬浮窗，
     * **不用重新构建装机** —— 调参是高频迭代，这条通路很关键。
     * （与 `model-profile.json` 的加载策略一致。）
     */
    private fun loadSoullinkProfileText(): String? {
        try {
            val mp = config.modelPath
            if (mp.startsWith("file://")) {
                val modelFile = java.io.File(android.net.Uri.parse(mp).path ?: "")
                val f = java.io.File(modelFile.parentFile, "soullink.profile.json")
                if (f.isFile) {
                    val text = f.readText()
                    L2DLog.i(L2DLog.Mod.AI, "使用模型目录内的 Soullink 档案",
                        "path=${f.absolutePath} bytes=${text.length}")
                    return text
                }
            }
        } catch (t: Throwable) {
            L2DLog.w(L2DLog.Mod.AI, "读取 sdcard Soullink 档案失败", "err=${t.javaClass.simpleName}")
        }
        return try {
            val text = assets.open("live2d/soullink.profile.json")
                .bufferedReader().use { it.readText() }
            L2DLog.i(L2DLog.Mod.AI, "使用内置 Soullink 档案", "bytes=${text.length}")
            text
        } catch (t: Throwable) {
            L2DLog.w(L2DLog.Mod.AI, "Soullink 档案缺失，引擎未启动", "err=${t.javaClass.simpleName}")
            null
        }
    }

    /**
     * 档案结构校验：必须是 JSON 对象，且 `actions` 或 `animations` 至少有一个非空。
     *
     * ★ v1.7.2：加入 `animations`（v2 新增的关键帧动画）。
     *   只认 actions 会把「纯动画档案」误判为非法而整份丢弃。
     *
     * ★ 这一步不能省。若不校验就直接 `evaluateJavascript`，非法 JSON 会让**整段脚本**
     * 解析失败 —— 页面里的 try/catch 根本不会执行，于是「已降级到内置动作库」
     * 这件事发生了却没有任何日志，排查时完全看不到线索（真机实测踩到）。
     */
    private fun isValidProfile(text: String): Boolean {
        return try {
            val o = org.json.JSONObject(text)
            val actions = o.optJSONArray("actions")
            val anims = o.optJSONArray("animations")
            (actions != null && actions.length() > 0) || (anims != null && anims.length() > 0)
        } catch (t: Throwable) {
            false
        }
    }

    private fun loadPage(wv: WebView) {
        val url = config.buildPageUrl()
        L2DLog.i(L2DLog.Mod.RENDER, "开始加载页面", "url=$url")
        wv.loadUrl(url)
    }

    /**
     * 受控重建 WebView。
     *
     * 顺序不可调换：先 loadUrl("about:blank") 让页面主动卸载 WebGL 上下文，
     * 再 destroy()，最后创建新的 WebView。否则部分 ROM 上会因上下文未释放而白屏。
     */
    private fun rebuildWebView(reason: String) {
        if (rebuildCount >= MAX_REBUILD_PER_SESSION) {
            L2DLog.e(L2DLog.Mod.SVC, "重建次数已耗尽，放弃", "count=$rebuildCount reason=$reason")
            return
        }
        rebuildCount++
        L2DLog.w(L2DLog.Mod.SVC, "触发重建 WebView", "count=$rebuildCount reason=$reason")

        val container = rootView as? android.widget.FrameLayout ?: return

        // 1. 卸载页面
        webView?.let { old ->
            try {
                old.loadUrl("about:blank")
            } catch (t: Throwable) {
                L2DLog.w(L2DLog.Mod.SVC, "loadUrl about:blank 失败", "err=${t.javaClass.simpleName}")
            }
            container.removeView(old)
            try {
                old.removeJavascriptInterface(Live2DJSBridge.JS_INTERFACE_NAME)
                old.destroy()
            } catch (t: Throwable) {
                L2DLog.w(L2DLog.Mod.SVC, "销毁旧 WebView 失败", "err=${t.javaClass.simpleName}")
            }
        }
        webView = null
        bridge = null

        // 旧的捕获窗口基于旧页面坐标系，先移除；新页面上报包围盒后会重建
        removeTouchCatcher()

        // 2. 新建
        val fresh = buildWebView()
        container.addView(
            fresh,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        webView = fresh
        loadPage(fresh)
    }

    private fun reloadPage() {
        val wv = webView
        if (wv == null) {
            attachOverlay()
        } else {
            loadPage(wv)
        }
    }

    // ------------------------------------------------------------------
    // 健康检查（看门狗）
    // ------------------------------------------------------------------

    private fun checkHealth() {
        if (!isRunning) return
        val b = bridge ?: return
        val last = b.lastHeartbeatAt
        if (last == 0L) {
            // 页面尚未产生首个心跳，给足冷启动时间（模型加载 + 编译着色器）
            return
        }
        val elapsed = System.currentTimeMillis() - last
        if (elapsed > HEARTBEAT_TIMEOUT_MS) {
            L2DLog.w(L2DLog.Mod.BRIDGE, "心跳超时，准备重建", "静默=${elapsed}ms lastError=${b.lastError}")
            rebuildWebView("heartbeat-timeout")
        }
    }

    override fun onRenderHeartbeat(type: String) {
        // 心跳只用于刷新时间戳，不在此处做重活
    }

    override fun onRenderError(message: String) {
        // WebGL 上下文丢失类错误无法原地恢复，直接重建
        if (message.contains("context-lost", ignoreCase = true)) {
            mainHandler.post { rebuildWebView("context-lost:$message") }
        }
    }

    /**
     * 页面上报模型包围盒。
     *
     * 这是区域化触摸的数据源。页面在加载完成、拖拽、缩放、旋转后调用，
     * 频率很低，因此直接在主线程处理即可。
     */
    override fun onHitAreaChanged(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        valid: Boolean
    ) {
        mainHandler.post {
            if (!valid) {
                // 模型不可见：移除捕获窗口，全屏回到纯装饰（触摸全部穿透）
                removeTouchCatcher()
                return@post
            }
            // 把捕获窗口精确对齐到模型包围盒：
            // 盒子内可触摸（模型响应），盒子外根本不属于任何窗口（桌面响应）。
            syncTouchCatcher(left, top, right, bottom)
        }
    }

    /**
     * 页面上报拖拽后的模型位置，写入配置持久化。
     *
     * 只更新存储，不触发页面重载——页面自身已经把模型挪到目标位置，
     * 重载反而会造成一次可见的闪动。
     */
    override fun onModelPositionChanged(centerX: Float, centerY: Float) {
        config.centerX = centerX
        config.centerY = centerY
        L2DLog.i(L2DLog.Mod.CFG, "模型位置已持久化", "cx=${centerX.toInt()} cy=${centerY.toInt()}")
    }

    // ------------------------------------------------------------------
    // 生命周期收尾
    // ------------------------------------------------------------------

    private fun stopSelfSafely() {
        isRunning = false
        config.enabled = false
        detachOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun detachOverlay() {
        mainHandler.removeCallbacks(watchdog)
        destroyWebView()
        // ★ 捕获窗口必须在这里一并移除。
        //
        // 此前只移除渲染窗口，导致捕获窗口泄漏：Service 停止后进程通常仍然存活
        // （Android 保留缓存进程），窗口不会随 token 消失，于是残留窗口继续可触摸，
        // 而它的 OnTouchListener 闭包指向**已销毁的旧 Service 实例**（webView 已为 null）
        // —— 触摸被吞掉却什么都不做。
        //
        // 多次启停后残留窗口不断累积，模型移动时撞进残留窗口的矩形，
        // 整个手势就被死窗口接管（Android 在 ACTION_DOWN 时绑定手势序列），
        // 表现为「拖动几次之后就拖不动了」。
        // 实测：3 轮「停→启」后 dumpsys 里累积出 5 个捕获窗口。
        removeTouchCatcher()
        rootView?.let { v ->
            try {
                windowManager.removeView(v)
            } catch (t: Throwable) {
                L2DLog.w(L2DLog.Mod.SVC, "移除渲染窗口失败", "err=${t.javaClass.simpleName}")
            }
        }
        rootView = null
        windowParams = null
    }

    private fun destroyWebView() {
        webView?.let { wv ->
            try {
                (wv.parent as? android.view.ViewGroup)?.removeView(wv)
            } catch (_: Throwable) {
            }
            try {
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.removeJavascriptInterface(Live2DJSBridge.JS_INTERFACE_NAME)
                wv.destroy()
            } catch (t: Throwable) {
                L2DLog.w(L2DLog.Mod.SVC, "销毁 WebView 失败", "err=${t.javaClass.simpleName}")
            }
        }
        webView = null
        bridge = null
    }

    /** v1.7.28：页面请求主动搭话 → 转发给 Activity（它持有 LLM 客户端） */
    override fun onProactive() {
        L2DLog.i(L2DLog.Mod.AI, "收到主动搭话请求")
        // 服务里没有 LLM 客户端（它在 Activity），所以走一个静态回调
        MainActivity.proactiveHandler?.invoke()
    }

    override fun onDestroy() {
        isRunning = false
        detachOverlay()
        super.onDestroy()
        L2DLog.i(L2DLog.Mod.SVC, "服务已销毁")
    }
}
