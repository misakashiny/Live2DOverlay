package com.live2d.overlay

import android.content.Context
import android.content.SharedPreferences

/**
 * 悬浮窗配置的单一数据源。
 *
 * 所有跨进程/跨组件共享的参数都收敛在这里，避免散落的字面量键名。
 * 服务与界面都通过本类读写，保证一致。
 */
class OverlayConfig(context: Context) {

    companion object {
        private const val PREF_NAME = "live2d_overlay_prefs"

        // ---- 关键键名 ----
        const val KEY_ENABLED = "enabled"                  // 悬浮窗总开关
        const val KEY_MODEL_PATH = "model_path"            // 模型 json 路径（file:// 或 content://）
        const val KEY_CENTER_X = "center_x"                // 设计坐标 X
        const val KEY_CENTER_Y = "center_y"                // 设计坐标 Y
        const val KEY_SCALE = "scale"                      // 缩放倍率
        const val KEY_QUALITY = "quality"                  // 渲染画质倍率 0.5~2.0
        const val KEY_FPS = "fps"                          // 目标帧率 15~60
        const val KEY_CLIP_BOTTOM = "clip_bottom"          // 裁切下边界（设计坐标 Y）
        const val KEY_CLIP_ENABLED = "clip_enabled"        // 是否启用裁切
        const val KEY_DESIGN_W = "design_w"                // 设计宽度基准
        const val KEY_DESIGN_H = "design_h"                // 设计高度基准
        const val KEY_AUTO_START = "auto_start"            // 开机自启
        const val KEY_OVERLAY_ALPHA = "overlay_alpha"      // 整体透明度 0~100
        const val KEY_TOUCH_ENABLED = "touch_enabled"      // 触摸交互总开关
        const val KEY_HIDE_WATERMARK = "hide_watermark"    // v1.2.0 隐藏水印（默认开）

        // ---- v1.5.0 新增 ----
        const val KEY_ACTION_CYCLE = "action_cycle"        // 短按动作按顺序轮播（默认随机）
        const val KEY_TAP_EFFECT = "tap_effect"            // 点击特效（默认开）
        const val KEY_EDGE_SNAP = "edge_snap"              // 边缘吸附（默认开）
        const val KEY_LOG_MIN_LEVEL = "log_min_level"      // 日志最低级别（默认 DEBUG）

        // ---- v1.7.2（AI 角色系统 P1）----
        const val KEY_ACTIVE_PERSONA = "active_persona_id" // 当前角色人设 id
        const val DEF_ACTIVE_PERSONA = "miku"

        // ---- v1.7.3（实验）----
        const val KEY_SOULLINK_ENABLED = "soullink_enabled" // Soullink 情绪引擎开关

        // ---- 默认值（对齐车机横屏 2560x720 场景）----
        const val DEF_CENTER_X = 1448f
        const val DEF_CENTER_Y = 420f
        const val DEF_SCALE = 1.0f
        const val DEF_QUALITY = 1.0f
        const val DEF_FPS = 60
        const val DEF_CLIP_BOTTOM = 546.5f
        const val DEF_DESIGN_W = 2560f
        const val DEF_DESIGN_H = 720f
        const val DEF_OVERLAY_ALPHA = 100
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    var modelPath: String
        get() = prefs.getString(KEY_MODEL_PATH, "") ?: ""
        set(v) = prefs.edit().putString(KEY_MODEL_PATH, v).apply()

    var centerX: Float
        get() = prefs.getFloat(KEY_CENTER_X, DEF_CENTER_X)
        set(v) = prefs.edit().putFloat(KEY_CENTER_X, v).apply()

    var centerY: Float
        get() = prefs.getFloat(KEY_CENTER_Y, DEF_CENTER_Y)
        set(v) = prefs.edit().putFloat(KEY_CENTER_Y, v).apply()

    var scale: Float
        get() = prefs.getFloat(KEY_SCALE, DEF_SCALE)
        set(v) = prefs.edit().putFloat(KEY_SCALE, v).apply()

    var quality: Float
        get() = prefs.getFloat(KEY_QUALITY, DEF_QUALITY)
        set(v) = prefs.edit().putFloat(KEY_QUALITY, v).apply()

    var fps: Int
        get() = prefs.getInt(KEY_FPS, DEF_FPS)
        set(v) = prefs.edit().putInt(KEY_FPS, v).apply()

    var clipBottom: Float
        get() = prefs.getFloat(KEY_CLIP_BOTTOM, DEF_CLIP_BOTTOM)
        set(v) = prefs.edit().putFloat(KEY_CLIP_BOTTOM, v).apply()

    var clipEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLIP_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_CLIP_ENABLED, v).apply()

    var designW: Float
        get() = prefs.getFloat(KEY_DESIGN_W, DEF_DESIGN_W)
        set(v) = prefs.edit().putFloat(KEY_DESIGN_W, v).apply()

    var designH: Float
        get() = prefs.getFloat(KEY_DESIGN_H, DEF_DESIGN_H)
        set(v) = prefs.edit().putFloat(KEY_DESIGN_H, v).apply()

    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_START, v).apply()

    var overlayAlpha: Int
        get() = prefs.getInt(KEY_OVERLAY_ALPHA, DEF_OVERLAY_ALPHA)
        set(v) = prefs.edit().putInt(KEY_OVERLAY_ALPHA, v).apply()

    /**
     * 触摸交互开关。
     *
     * 默认开启：模型区域可触摸（点击有反应、可拖拽），区域外穿透桌面。
     * 关闭后回到「整窗穿透」的纯装饰模式，完全不干扰桌面操作。
     */
    var touchEnabled: Boolean
        get() = prefs.getBoolean(KEY_TOUCH_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_TOUCH_ENABLED, v).apply()

    /**
     * v1.2.0（R6）隐藏模型自带水印。
     *
     * 本模型的「水印」是 Param137 控制的一层贴图 Mesh，且是**反向**语义：
     * 值为 1 时隐藏、为 0（默认）时显示。默认开启隐藏。
     *
     * 注意：该参数只影响 Param137 对应的那一层。若模型把版权信息直接烘焙在
     * 主纹理上（本模型不属于此类），任何参数都无法去除。
     */
    var hideWatermark: Boolean
        get() = prefs.getBoolean(KEY_HIDE_WATERMARK, true)
        set(v) = prefs.edit().putBoolean(KEY_HIDE_WATERMARK, v).apply()

    /**
     * v1.5.0：短按动作是否「按顺序轮播」。
     *
     * 默认 false（随机）。调试时开启可逐个走完动作库 —— 随机模式
     * 可能连续多次抽不到某个动作，无法确认它是否正常。
     */
    var actionCycle: Boolean
        get() = prefs.getBoolean(KEY_ACTION_CYCLE, false)
        set(v) = prefs.edit().putBoolean(KEY_ACTION_CYCLE, v).apply()

    /** v1.5.0：点击时是否播放涟漪/星光特效 */
    var tapEffect: Boolean
        get() = prefs.getBoolean(KEY_TAP_EFFECT, true)
        set(v) = prefs.edit().putBoolean(KEY_TAP_EFFECT, v).apply()

    /** v1.5.0：拖动松手后是否吸附到屏幕左右边缘 */
    var edgeSnap: Boolean
        get() = prefs.getBoolean(KEY_EDGE_SNAP, true)
        set(v) = prefs.edit().putBoolean(KEY_EDGE_SNAP, v).apply()

    /**
     * v1.5.0：日志最低级别（持久化，避免每次启动回到 DEBUG）。
     * 存的是级别 tag（"V"/"D"/"I"/"W"/"E"），空串表示「全部」。
     */
    var logMinLevel: String
        get() = prefs.getString(KEY_LOG_MIN_LEVEL, "D") ?: "D"
        set(v) = prefs.edit().putString(KEY_LOG_MIN_LEVEL, v).apply()

    /**
     * v1.7.2（AI 角色系统 P1）：当前生效的角色人设 id。
     *
     * 只存 id，不存整份 JSON —— 人设文件可能被用户改（sdcard 覆盖），
     * 存副本会出现「改了文件但界面还用旧值」的困惑。
     */
    var activePersonaId: String
        get() = prefs.getString(KEY_ACTIVE_PERSONA, DEF_ACTIVE_PERSONA) ?: DEF_ACTIVE_PERSONA
        set(v) = prefs.edit().putString(KEY_ACTIVE_PERSONA, v).apply()

    /**
     * v1.7.3（实验）：是否启用 Soullink Emotion 引擎接管情绪与动作。
     *
     * **默认关闭**。原因（详见 docs/33）：
     *   参数审计证明 Soullink 与本项目内置实现会写**同一批 17 个参数**，
     *   同时开必然逐帧互相覆盖（docs/00 §五「位置所有权必须唯一」那类坑）。
     *   所以开启后页面会主动让位：内置 idle 与动作库全部停止写参数。
     *
     * 关闭时行为与 v1.7.2 完全一致 —— 这是刻意设计的可回退开关。
     */
    var soullinkEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOULLINK_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_SOULLINK_ENABLED, v).apply()

    /**
     * 构造 WebView 页面 URL。
     *
     * 参数协议与原版 MikuCarLauncher 完全对齐，便于直接复用同一份 live2d_decor.html。
     * 悬浮窗场景下 clip 默认关闭（0）：模型自带 alpha 通道，无需矩形裁切。
     *
     * v1.2.0（R2）：触摸模式下 **不再下发 cx/cy**，改由页面以「自动模式」处理。
     *
     * 原因：这两个参数会被页面的待机逻辑每帧用来覆写 model.x/y，
     * 与用户拖拽的结果互相打架（表现为「拖走了又弹回来」）。
     * 触摸模式下坐标归页面/用户接管，设置页的 x/y 滑条仅作只读展示。
     */
    fun buildPageUrl(): String {
        val sb = StringBuilder("file:///android_asset/live2d/live2d_decor.html?")
        sb.append("model=").append(android.net.Uri.encode(modelPath))
        sb.append("&dw=").append(fmt(designW))
        sb.append("&dh=").append(fmt(designH))
        // 仅非触摸模式才下发坐标；触摸模式让页面自主定位
        if (!touchEnabled) {
            sb.append("&cx=").append(fmt(centerX))
            sb.append("&cy=").append(fmt(centerY))
        } else {
            sb.append("&autoPos=1")
        }
        sb.append("&scale=").append(fmt(scale))
        sb.append("&quality=").append(fmt(quality))
        sb.append("&fps=").append(fps)
        sb.append("&clip=").append(if (clipEnabled) "1" else "0")
        sb.append("&clipBottom=").append(fmt(clipBottom))
        sb.append("&touch=").append(if (touchEnabled) "1" else "0")
        // v1.2.0（R6）：watermark=1 表示「显示水印」；默认隐藏
        sb.append("&watermark=").append(if (hideWatermark) "0" else "1")
        // v1.5.0：动作轮播 / 点击特效 / 边缘吸附
        sb.append("&cycle=").append(if (actionCycle) "1" else "0")
        sb.append("&fx=").append(if (tapEffect) "1" else "0")
        sb.append("&snap=").append(if (edgeSnap) "1" else "0")
        // v1.7.3（实验）：Soullink 情绪引擎。默认 0 = 用内置实现。
        sb.append("&soullink=").append(if (soullinkEnabled) "1" else "0")
        return sb.toString()
    }

    private fun fmt(v: Float): String {
        // 去掉多余小数位，避免 URL 冗长
        return if (v == v.toInt().toFloat()) v.toInt().toString() else String.format("%.2f", v)
    }
}
