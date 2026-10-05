package com.live2d.overlay

import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * Java <-> JS 双向通信桥（JS 侧调用 Java 的入口）。
 *
 * 与页面 `live2d_decor.html` 的约定：
 *   window.MikuLive2DAndroid.heartbeat(type)        渲染循环心跳（从 Pixi ticker 内部上报）
 *   window.MikuLive2DAndroid.reportError(msg)       渲染异常上报
 *   window.MikuLive2DAndroid.setHitArea(json)       上报模型屏幕包围盒（触摸命中判定用）
 *   window.MikuLive2DAndroid.setModelPos(cx, cy)    上报拖拽后的模型设计坐标（持久化）
 *
 * 注意：所有被 @JavascriptInterface 标注的方法都在 WebView 的 JS 线程执行，
 * 不能直接操作 UI。这里只做状态回传，由 [OverlayService] 派发到主线程处理。
 */
class Live2DJSBridge(private val listener: Listener) {

    interface Listener {
        fun onProactive()
        /** 收到来自渲染 ticker 内部的心跳，说明 WebGL 上下文健康 */
        fun onRenderHeartbeat(type: String)

        /** 收到渲染异常上报（WebGL 上下文丢失、脚本错误等） */
        fun onRenderError(message: String)

        /**
         * 收到模型屏幕包围盒。
         *
         * 用于把「全屏不可触摸」的悬浮窗，改造成「仅模型区域可触摸」。
         * 坐标单位是 WebView 的 CSS 像素，与 Android 的 View 坐标系一致（同为设备无关像素）。
         *
         * @param left   包围盒左边界（CSS px）
         * @param top    包围盒上边界（CSS px）
         * @param right  包围盒右边界（CSS px）
         * @param bottom 包围盒下边界（CSS px）
         * @param valid  false 表示模型尚未就绪或不可见，此时应回到全屏穿透
         */
        fun onHitAreaChanged(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            valid: Boolean
        )

        /** 用户拖拽模型后上报新的设计坐标，供持久化 */
        fun onModelPositionChanged(centerX: Float, centerY: Float)
    }

    /** 最近一次心跳时间戳，用于服务侧判断是否假死 */
    @Volatile
    var lastHeartbeatAt: Long = 0L
        private set

    /** 最近一次错误信息，便于界面展示排查 */
    @Volatile
    var lastError: String? = null
        private set

    /** 最近一次上报的包围盒，服务侧据此做命中判定 */
    @Volatile
    var hitLeft: Float = 0f
        private set
    @Volatile
    var hitTop: Float = 0f
        private set
    @Volatile
    var hitRight: Float = 0f
        private set
    @Volatile
    var hitBottom: Float = 0f
        private set
    @Volatile
    var hitValid: Boolean = false
        private set

    /**
     * 页面 viewport 尺寸（CSS px）。
     *
     * 关键：Android WebView 会把 CSS 像素按 devicePixelRatio 缩放后再渲染，
     * 因此页面的 window.innerWidth 通常**远小于** View 的实际像素宽度。
     * 例如本机上 View 是 2560px 宽，而页面 innerWidth 只有 1138px。
     *
     * dispatchTouchEvent 拿到的 e.x/e.y 是 View 像素坐标，
     * 必须换算到 CSS 坐标才能和页面上报的包围盒比较：
     *     cssX = viewX * (viewportW / viewW)
     */
    @Volatile
    var viewportW: Float = 0f
        private set
    @Volatile
    var viewportH: Float = 0f
        private set

    /**
     * 模型是否「由用户手动移动过」。
     *
     * 一旦用户拖动过模型，页面在上报包围盒时就会带上 manual=true，
     * 服务侧据此暂缓自动居中，避免与用户意图打架。
     */
    @Volatile
    var lastManualMove: Boolean = false
        private set

    @JavascriptInterface
    fun heartbeat(type: String?) {
        // v1.7.28：页面请求「主动搭话」
        @JavascriptInterface
        fun onProactive() {
            listener.onProactive()
        }

        lastHeartbeatAt = System.currentTimeMillis()
        listener.onRenderHeartbeat(type ?: "tick")
    }

    @JavascriptInterface
    fun reportError(message: String?) {
        val msg = message ?: "unknown"
        lastError = msg
        Log.w(TAG, "render error reported from JS: $msg")
        listener.onRenderError(msg)
    }

    /**
     * 接收模型屏幕包围盒。
     *
     * 页面每次布局变化（加载完成、拖拽、缩放、屏幕旋转）都会调用一次，
     * 频率远低于渲染帧率，因此这里不做节流。
     *
     * 传入 JSON 而非四个独立参数，是为了回避 @JavascriptInterface 的
     * 参数类型推断差异（部分 ROM 的 WebView 对多参数重载解析不稳定）。
     */
    @JavascriptInterface
    fun setHitArea(json: String?) {
        if (json.isNullOrEmpty()) return
        try {
            val o = JSONObject(json)
            val l = o.optDouble("l", 0.0).toFloat()
            val t = o.optDouble("t", 0.0).toFloat()
            val r = o.optDouble("r", 0.0).toFloat()
            val b = o.optDouble("b", 0.0).toFloat()
            val valid = o.optBoolean("v", false)

            hitLeft = l
            hitTop = t
            hitRight = r
            hitBottom = b
            hitValid = valid
            lastManualMove = o.optBoolean("manual", false)

            // viewport 尺寸随包围盒一并上报，用于把 View 像素换算到 CSS 像素
            val vw = o.optDouble("vw", 0.0).toFloat()
            val vh = o.optDouble("vh", 0.0).toFloat()
            if (vw > 0f) viewportW = vw
            if (vh > 0f) viewportH = vh

            listener.onHitAreaChanged(l, t, r, b, valid)
        } catch (e: Throwable) {
            Log.w(TAG, "setHitArea parse failed: $json", e)
        }
    }

    /** 接收拖拽后的模型设计坐标（由页面归一化到 designW/designH 坐标系） */
    @JavascriptInterface
    fun setModelPos(cx: String?, cy: String?) {
        val x = cx?.toFloatOrNull() ?: return
        val y = cy?.toFloatOrNull() ?: return
        listener.onModelPositionChanged(x, y)
    }

    /**
     * v1.3.0：接收页面侧的结构化日志。
     *
     * 页面里的 LOG 核心会三路输出，其中一路直接调到这里，
     * 好处是绕开 `console.log` → `onConsoleMessage` 的字符串解析，
     * 保留原始的级别/模块/键值对结构，在 App 内的日志列表里可以正确过滤。
     *
     * 注意：本方法在 WebView 的 JS 线程执行，L2DLog 内部已是线程安全的队列写入，
     * 因此这里不需要再切线程。
     */
    @JavascriptInterface
    fun log(level: String?, module: String?, message: String?, extras: String?) {
        try {
            L2DLog.writeFromJs(level ?: "D", module ?: "JS", message ?: "", extras ?: "")
        } catch (t: Throwable) {
            // 日志失败绝不能影响渲染
        }
    }

    companion object {
        const val TAG = "Live2DJSBridge"

        /**
         * 桥接对象在 window 上暴露的名字。
         *
         * 必须与 assets/live2d/live2d_decor.html 内的引用完全一致：
         * 页面调用的是 window.MikuLive2DAndroid.*。
         * 该页面是从 MikuCarLauncher 原样复用的，保持名称不变可避免改页面。
         */
        const val JS_INTERFACE_NAME = "MikuLive2DAndroid"
    }
}
