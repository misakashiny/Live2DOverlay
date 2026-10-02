package com.live2d.overlay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.live2d.overlay.databinding.ActivityMainBinding
import java.io.File

/**
 * 控制台界面。
 *
 * 功能分区：
 *  1. 权限状态（悬浮窗 / 通知 / 存储读取）
 *  2. 模型选择（SAF 文件选择器）
 *  3. 显示参数（位置、缩放、画质、帧率、透明度）
 *  4. 开关与自启
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "MainActivity"

        /**
         * 日志级别存档用的哨兵值：对应下拉第 0 项「全部」。
         * 「全部」不是某个 Level（它连 VERBOSE 都放行），无法用 V/D/I/W/E 表示。
         */
        const val LOG_LEVEL_ALL = "ALL"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var config: OverlayConfig

    /**
     * 模型目录选择器（SAF 目录授权）。
     *
     * 为什么选目录而不是选文件：Live2D 模型是「描述文件 + 若干兄弟资源」的集合，
     * 页面必须能按相对路径访问同目录的 .moc3 与纹理，因此需要目录级访问能力。
     * 选中目录后自动扫描其中的 .model3.json / .model.json，避免用户手点错文件。
     */
    private val pickModelDir = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Throwable) {
            // 部分来源不支持持久化授权，退化为临时授权
        }
        handlePickedDirectory(uri)
    }

    /**
     * 把选中的目录解析为真实路径并扫描模型。
     *
     * SAF 返回的 tree URI 需反解出文件系统路径，因为页面用的是 file:// 相对路径解析。
     * 反解失败时给出明确指引，引导用户改用「手动输入路径」。
     */
    private fun handlePickedDirectory(treeUri: Uri) {
        val dir = ModelLocator.treeUriToFile(this, treeUri)
        if (dir == null || !dir.exists()) {
            toast("无法解析该目录的真实路径，请改用「手动输入路径」")
            return
        }
        applyScannedDirectory(dir)
    }

    /** 扫描目录并自动选中模型；多个模型时取第一个并提示 */
    private fun applyScannedDirectory(dir: File) {
        val result = ModelLocator.scan(dir)
        if (result.models.isEmpty()) {
            toast(result.error ?: "该目录下未找到模型文件")
            binding.tvModelPath.text = "扫描 ${dir.absolutePath}：${result.error}"
            return
        }

        val picked = result.models.first()
        val problem = ModelLocator.validate(picked)

        if (problem != null) {
            // 资源不全时仍然记录，但明确告警，避免用户以为是 App 坏了
            config.modelPath = "file://${picked.absolutePath}"
            refreshModelLabel()
            toast("模型已选择，但${problem}")
            return
        }

        config.modelPath = "file://${picked.absolutePath}"
        refreshModelLabel()
        val extra = if (result.models.size > 1) "（该目录有 ${result.models.size} 个模型，已选第一个）" else ""
        toast("模型已就绪$extra")
    }

    /** 刷新模型路径显示：只显示相对 /sdcard 的短路径，避免换行刷屏 */
    private fun refreshModelLabel() {
        val p = config.modelPath
        if (p.isEmpty()) {
            binding.tvModelPath.text = "未选择（点击下方按钮选择模型所在目录）"
        } else {
            val short = p.removePrefix("file:///sdcard/").removePrefix("file:///storage/emulated/0/")
            binding.tvModelPath.text = short
        }
    }

    /**
     * 通知权限（Android 13+）。
     *
     * 注意：回调里**只能**刷新状态，绝不能再次触发权限申请，
     * 否则会形成 refreshState → launch → 回调 → refreshState 的无限递归，
     * 最终抛 StackOverflowError 崩溃。申请动作只在 onCreate 中发起一次。
     */
    private val requestNotification = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) toast("未授予通知权限，前台服务通知可能不显示")
        refreshState()
    }

    /** 标记通知权限是否已在本次会话中请求过，避免重复弹窗与递归 */
    private var notificationRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        config = OverlayConfig(this)

        setupUi()
        refreshState()
        setupLogPanel()
        maybeRequestNotification()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
        maybeRequestNotification()
        refreshLogView()
    }

    override fun onDestroy() {
        // 反注册日志监听与自动刷新，避免界面销毁后回调仍持有 Activity
        logListener?.invoke()
        logListener = null
        binding.root.removeCallbacks(logAutoRefresh)
        super.onDestroy()
    }

    /** 仅在「未授权且未请求过」时发起一次通知权限申请 */
    private fun maybeRequestNotification() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (notificationRequested) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        notificationRequested = true
        requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ------------------------------------------------------------------
    // UI 装配
    // ------------------------------------------------------------------

    private fun setupUi() {
        refreshModelLabel()

        // 首选：选目录（自动扫描出模型文件，且保证页面能按相对路径读到兄弟资源）
        binding.btnPickModel.setOnClickListener {
            pickModelDir.launch(null)
        }

        // 备用：手动输入路径。SAF 反解失败、或模型放在非常规位置时使用
        binding.btnManualPath.setOnClickListener {
            showManualPathDialog()
        }

        // 快捷：一键扫描默认模型目录
        binding.btnScanDefault.setOnClickListener {
            applyScannedDirectory(ModelLocator.defaultModelsDir())
        }

        // Android 11+ 需要「所有文件访问权限」，否则 WebView 无法用 file:// 读 /sdcard 下的模型
        binding.btnAllFiles.setOnClickListener {
            openAllFilesSettings()
        }

        binding.btnOverlayPermission.setOnClickListener {
            openOverlaySettings()
        }

        binding.switchEnabled.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                startOverlay()
            } else {
                stopOverlay()
            }
        }

        binding.switchAutoStart.setOnCheckedChangeListener { _, checked ->
            config.autoStart = checked
        }
        binding.switchAutoStart.isChecked = config.autoStart

        // 触摸交互开关：切换后立即推送到运行中的服务，无需重启悬浮窗
        binding.switchTouch.setOnCheckedChangeListener { _, checked ->
            config.touchEnabled = checked
            pushTouchMode(checked)
            // v1.2.0（R2）：触摸开启后坐标由拖拽接管，滑条置灰
            applyPositionControlState(checked)
        }
        binding.switchTouch.isChecked = config.touchEnabled

        // v1.2.0（R6）：隐藏水印开关。水印层的显隐由 URL 参数决定，
        // 因此需要重载页面才能生效。
        binding.switchWatermark.setOnCheckedChangeListener { _, checked ->
            config.hideWatermark = checked
            pushLiveUpdate(needReload = true)
        }
        binding.switchWatermark.isChecked = config.hideWatermark

        // ---- v1.5.0：交互反馈三项 ----
        // 点击特效与边缘吸附只影响页面行为，可通过页面接口热切换，
        // 不必重载模型（重载要数秒）。
        binding.switchTapFx.isChecked = config.tapEffect
        binding.switchTapFx.setOnCheckedChangeListener { _, checked ->
            config.tapEffect = checked
            pushPageOption("fx", checked)
        }

        binding.switchEdgeSnap.isChecked = config.edgeSnap
        binding.switchEdgeSnap.setOnCheckedChangeListener { _, checked ->
            config.edgeSnap = checked
            pushPageOption("snap", checked)
        }

        binding.switchActionCycle.isChecked = config.actionCycle
        binding.switchActionCycle.setOnCheckedChangeListener { _, checked ->
            config.actionCycle = checked
            pushPageOption("cycle", checked)
        }

        // 透明度
        binding.seekAlpha.max = 100
        binding.seekAlpha.progress = config.overlayAlpha
        binding.tvAlphaValue.text = "${config.overlayAlpha}%"
        binding.seekAlpha.setOnSeekBarChangeListener(
            SimpleSeekListener { value ->
                config.overlayAlpha = value
                binding.tvAlphaValue.text = "$value%"
                // v1.4.0（Bug-A）：透明度走专用通道，只改窗口属性不重载页面
                pushAlpha(value)
            }
        )

        // 缩放
        binding.seekScale.max = 200
        binding.seekScale.progress = (config.scale * 100).toInt().coerceIn(20, 200)
        binding.tvScaleValue.text = String.format("%.2fx", config.scale)
        binding.seekScale.setOnSeekBarChangeListener(
            SimpleSeekListener { value ->
                val s = value / 100f
                config.scale = s
                binding.tvScaleValue.text = String.format("%.2fx", s)
                pushLiveUpdate()
            }
        )

        // 水平位置
        binding.seekCx.max = config.designW.toInt()
        binding.seekCx.progress = config.centerX.toInt().coerceIn(0, config.designW.toInt())
        binding.tvCxValue.text = config.centerX.toInt().toString()
        binding.seekCx.setOnSeekBarChangeListener(
            SimpleSeekListener { value ->
                config.centerX = value.toFloat()
                binding.tvCxValue.text = value.toString()
                pushLiveUpdate()
            }
        )

        // 垂直位置
        binding.seekCy.max = config.designH.toInt()
        binding.seekCy.progress = config.centerY.toInt().coerceIn(0, config.designH.toInt())
        binding.tvCyValue.text = config.centerY.toInt().toString()
        binding.seekCy.setOnSeekBarChangeListener(
            SimpleSeekListener { value ->
                config.centerY = value.toFloat()
                binding.tvCyValue.text = value.toString()
                pushLiveUpdate()
            }
        )

        // v1.2.0（R2）：触摸模式下坐标滑条由模型位置自动接管，置灰提示
        applyPositionControlState(config.touchEnabled)

        // 画质
        binding.seekQuality.max = 150
        binding.seekQuality.progress = ((config.quality - 0.5f) * 100).toInt().coerceIn(0, 150)
        binding.tvQualityValue.text = String.format("%.2fx", config.quality)
        binding.seekQuality.setOnSeekBarChangeListener(
            SimpleSeekListener { value ->
                val q = 0.5f + value / 100f
                config.quality = q
                binding.tvQualityValue.text = String.format("%.2fx", q)
                // 画质变更需重建页面才能生效（影响 renderer resolution）
                pushLiveUpdate(needReload = true)
            }
        )

        // 帧率
        binding.seekFps.max = 45  // 15 ~ 60
        binding.seekFps.progress = (config.fps - 15).coerceIn(0, 45)
        binding.tvFpsValue.text = "${config.fps}"
        binding.seekFps.setOnSeekBarChangeListener(
            SimpleSeekListener { value ->
                val f = 15 + value
                config.fps = f
                binding.tvFpsValue.text = "$f"
                pushLiveUpdate(needReload = true)
            }
        )

        binding.btnApply.setOnClickListener {
            if (OverlayService.isRunning) {
                pushLiveUpdate(needReload = true)
                toast("参数已应用")
            } else {
                toast("悬浮窗未运行")
            }
        }
    }

    // ------------------------------------------------------------------
    // 状态刷新
    // ------------------------------------------------------------------

    private fun refreshState() {
        val canOverlay = Settings.canDrawOverlays(this)

        binding.tvOverlayStatus.text = if (canOverlay) "已授权" else "未授权"
        binding.tvOverlayStatus.setTextColor(
            ContextCompat.getColor(this, if (canOverlay) R.color.ok_green else R.color.warn_amber)
        )
        binding.btnOverlayPermission.isEnabled = !canOverlay

        val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
        binding.tvNotifStatus.text = if (notifOk) "已授权" else "未授权"
        binding.tvNotifStatus.setTextColor(
            ContextCompat.getColor(this, if (notifOk) R.color.ok_green else R.color.warn_amber)
        )

        binding.tvServiceStatus.text = if (OverlayService.isRunning) "运行中" else "已停止"
        binding.tvServiceStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (OverlayService.isRunning) R.color.ok_green else R.color.text_secondary
            )
        )

        binding.switchEnabled.setOnCheckedChangeListener(null)
        binding.switchEnabled.isChecked = OverlayService.isRunning
        binding.switchEnabled.setOnCheckedChangeListener { _, checked ->
            if (checked) startOverlay() else stopOverlay()
        }

        // 未授权时引导先授权
        binding.switchEnabled.isEnabled = canOverlay

        // 注意：此处不做任何权限申请。
        // 权限申请统一由 maybeRequestNotification() 在生命周期入口发起一次，
        // 若在此处调 launch，回调会再进入本方法，形成无限递归导致 StackOverflowError。
    }

    // ------------------------------------------------------------------
    // 操作
    // ------------------------------------------------------------------

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            toast("请先授予悬浮窗权限")
            openOverlaySettings()
            return
        }
        if (config.modelPath.isEmpty()) {
            toast("请先选择 Live2D 模型文件")
            return
        }
        config.enabled = true
        val svc = Intent(this, OverlayService::class.java).apply {
            setAction(OverlayService.ACTION_START)
        }
        safeStartService(svc)
        // 服务状态回传略有延迟，稍后刷新
        binding.root.postDelayed({ refreshState() }, 800)
    }

    private fun stopOverlay() {
        val svc = Intent(this, OverlayService::class.java).apply {
            setAction(OverlayService.ACTION_STOP)
        }
        // 服务可能已经是前台服务，统一走 startForegroundService，
        // 避免 Android 8+ 对 startService 的启动限制触发 IllegalStateException。
        safeStartService(svc)
        config.enabled = false
        binding.root.postDelayed({ refreshState() }, 500)
    }

    /**
     * 把触摸交互开关推送到运行中的服务。
     *
     * 走 ACTION_SET_TOUCHABLE 而非页面重载：切换开关只影响触摸路由，
     * 重载页面会白白重新加载一次模型（约数秒），体验上不可接受。
     */
    private fun pushTouchMode(enabled: Boolean) {
        if (!OverlayService.isRunning) return
        val svc = Intent(this, OverlayService::class.java).apply {
            setAction(OverlayService.ACTION_SET_TOUCHABLE)
            putExtra("enabled", enabled)
        }
        safeStartService(svc)
        toast(if (enabled) "触摸交互已开启（仅模型区域响应）" else "触摸交互已关闭（完全穿透）")
    }

    /**
     * v1.2.0（R2）：根据触摸开关状态同步「坐标滑条」的可用性。
     *
     * 触摸开启后，模型的 x/y 完全由用户拖拽决定（也由页面自动落位），
     * 设置页的滑条不再参与定位 —— 如果继续让用户拖滑条，会造成
     * 「设了坐标但拖一下就失效」的困惑。因此置灰并给出明确提示。
     */
    private fun applyPositionControlState(touchEnabled: Boolean) {
        val enabled = !touchEnabled
        binding.seekCx.isEnabled = enabled
        binding.seekCy.isEnabled = enabled
        binding.seekCx.alpha = if (enabled) 1.0f else 0.4f
        binding.seekCy.alpha = if (enabled) 1.0f else 0.4f
        binding.tvPosHint.visibility = if (enabled) View.GONE else View.VISIBLE
        binding.tvPosHint.text = "触摸模式下坐标由模型位置自动接管：直接长按拖动模型即可调整位置，松手后自动记住。"
    }

    /**
     * v1.5.0：把单个页面开关热推送到运行中的悬浮窗。
     *
     * 走 ACTION_DEBUG_JS 注入页面接口，而不是重载页面 ——
     * 这些开关只改页面行为标志，重载会白白重新加载一次模型（数秒）。
     *
     * @param key 页面参数名：fx（点击特效）/ snap（边缘吸附）/ cycle（动作轮播）
     */
    private fun pushPageOption(key: String, on: Boolean) {
        if (!OverlayService.isRunning) return
        val js = when (key) {
            "cycle" -> "window.__mikuLive2DSetActionCycle && window.__mikuLive2DSetActionCycle(${if (on) 1 else 0})"
            "fx", "snap" ->
                "window.__mikuLive2DSetOption && window.__mikuLive2DSetOption('$key', ${if (on) 1 else 0})"
            else -> return
        }
        val svc = Intent(this, OverlayService::class.java).apply {
            setAction(OverlayService.ACTION_DEBUG_JS)
            putExtra("js", js)
        }
        safeStartService(svc)
    }

    /**
     * v1.4.0（Bug-A）：把透明度推送到运行中的悬浮窗。
     *
     * 单独开一条通道的原因：透明度是滑条连续调节的参数，
     * 若复用 ACTION_REFRESH 会每次重载页面（重新加载模型，数秒白屏）。
     * 走到窗口级 alpha 则是一次 updateViewLayout，实时且无闪烁。
     */
    private fun pushAlpha(value: Int) {
        if (!OverlayService.isRunning) return
        val svc = Intent(this, OverlayService::class.java).apply {
            setAction(OverlayService.ACTION_SET_ALPHA)
            putExtra("alpha", value)
        }
        safeStartService(svc)
    }

    /**
     * 将参数推送到正在运行的悬浮窗页面。
     *
     * 悬浮窗在模型之外的区域不可触摸，无法通过拖动实时微调，
     * 因此所有参数变更统一走页面重载，保证配置与渲染状态严格一致。
     */
    private fun pushLiveUpdate(needReload: Boolean = false) {
        if (!OverlayService.isRunning) return
        val svc = Intent(this, OverlayService::class.java).apply {
            setAction(OverlayService.ACTION_REFRESH)
        }
        safeStartService(svc)
    }

    /** 统一的服务启动入口，兼容 Android 8+ 的前台服务限制 */
    private fun safeStartService(intent: Intent) {
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (t: Throwable) {
            Log.w(TAG, "startForegroundService failed, fallback to startService", t)
            try {
                startService(intent)
            } catch (t2: Throwable) {
                Log.e(TAG, "startService failed too", t2)
            }
        }
    }

    /**
     * 手动输入模型路径。
     *
     * 作为 SAF 的兜底：部分 ROM 的 tree URI 无法反解为真实路径，
     * 或模型放在非常规位置时，用户可直接填写绝对路径。
     */
    private fun showManualPathDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "/sdcard/Live2DModels/miku/miku.model3.json"
            setSingleLine(false)
            setText(
                config.modelPath
                    .removePrefix("file://")
                    .ifEmpty { ModelLocator.defaultModelsDir().absolutePath }
            )
            setSelection(text.length)
        }
        val pad = (resources.displayMetrics.density * 20).toInt()
        val wrap = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("手动输入模型路径")
            .setMessage("填 .model3.json 或 .model.json 的完整路径")
            .setView(wrap)
            .setPositiveButton("确定") { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isEmpty()) return@setPositiveButton
                val f = File(raw.removePrefix("file://"))
                if (!f.exists()) {
                    toast("文件不存在：${f.absolutePath}")
                    return@setPositiveButton
                }
                val problem = ModelLocator.validate(f)
                config.modelPath = "file://${f.absolutePath}"
                refreshModelLabel()
                if (problem == null) toast("模型已就绪") else toast("已记录，但${problem}")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 打开「所有文件访问权限」设置页（Android 11+）。
     *
     * 为什么需要：WebView 加载的页面用 `file://` 访问 `/sdcard/` 下的模型资源时，
     * 分区存储会拦截该访问，导致模型加载失败（日志表现为 net::ERR_ACCESS_DENIED）。
     * 授予 MANAGE_EXTERNAL_STORAGE 后即可正常读取。
     */
    private fun openAllFilesSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            toast("当前系统版本无需此项授权")
            return
        }
        if (android.os.Environment.isExternalStorageManager()) {
            toast("已拥有所有文件访问权限")
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (t: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (t2: Throwable) {
                toast("无法打开该设置页，请手动在系统设置中授予")
            }
        }
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            // 少数 ROM 无此界面，退回总入口
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (t2: Throwable) {
                toast("无法打开悬浮窗设置，请手动在系统设置中授权")
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ==================================================================
    // 运行日志面板（v1.3.0）
    // ==================================================================

    /** 日志监听反注册句柄 */
    private var logListener: (() -> Unit)? = null

    /** 界面当前的模块 / 关键字过滤条件（空串表示不限） */
    private var logModuleFilter = ""

    /** 日志区最近一次触摸的纵坐标（用于长按定位行号） */
    private var logLastTouchY = 0f
    private var logTextFilter = ""

    /**
     * 自动刷新节流器。
     *
     * 为什么需要节流：悬浮窗运行时日志以「帧」的频率产生（RENDER/TOUCH 模块），
     * 若每条都触发一次列表重绘，会与渲染线程抢主线程，直接把界面拖卡。
     * 这里统一合并为 400ms 一次，用户体验上仍然是「实时」的。
     */
    private val logAutoRefresh = Runnable { refreshLogView() }

    /** 级别下拉项：文案 → 对应的 Level（null 表示不限级别） */
    private val logLevelLabels = listOf("全部", "verbose+", "debug+", "info+", "warn+", "error")
    private val logLevelValues: List<L2DLog.Level?> = listOf(
        null, L2DLog.Level.VERBOSE, L2DLog.Level.DEBUG,
        L2DLog.Level.INFO, L2DLog.Level.WARN, L2DLog.Level.ERROR
    )

    private fun setupLogPanel() {
        L2DLog.init(this)

        // 级别下拉
        val adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, logLevelLabels
        )
        binding.spinnerLogLevel.adapter = adapter
        // v1.5.0：级别从 SharedPreferences 恢复。
        // 旧行为每次开界面都回到 DEBUG，用户调到 WARN 看错误，下次进来又被噪音淹没。
        // 存档约定：第 0 项「全部」没有对应 Level，用哨兵值 "ALL" 表示。
        val restored = if (config.logMinLevel == LOG_LEVEL_ALL) {
            0
        } else {
            logLevelValues.indexOf(L2DLog.Level.fromTag(config.logMinLevel)).coerceAtLeast(0)
        }
        binding.spinnerLogLevel.setSelection(restored)
        L2DLog.minLevel = logLevelValues.getOrNull(restored)
        binding.spinnerLogLevel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val lv = logLevelValues.getOrNull(position)
                L2DLog.minLevel = lv
                config.logMinLevel = lv?.tag ?: LOG_LEVEL_ALL
                refreshLogView()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 开关：采集总闸
        binding.switchLog.isChecked = L2DLog.enabled
        binding.switchLog.setOnCheckedChangeListener { _, checked ->
            L2DLog.enabled = checked
            refreshLogView()
        }

        // 开关：logcat 镜像
        binding.cbLogCat.isChecked = L2DLog.mirrorToLogcat
        binding.cbLogCat.setOnCheckedChangeListener { _, checked ->
            L2DLog.mirrorToLogcat = checked
        }

        binding.etLogModule.setText(logModuleFilter)
        binding.etLogText.setText(logTextFilter)

        binding.btnLogFilter.setOnClickListener {
            logModuleFilter = binding.etLogModule.text.toString().trim()
            logTextFilter = binding.etLogText.text.toString().trim()
            refreshLogView()
        }

        binding.btnLogRefresh.setOnClickListener { refreshLogView() }

        binding.btnLogClear.setOnClickListener {
            L2DLog.clear()
            refreshLogView()
            toast("日志缓冲已清空")
        }

        binding.btnLogExport.setOnClickListener { exportLogs() }
        binding.btnLogExportJson.setOnClickListener { exportLogs(json = true) }

        // 记录最近一次触摸的纵坐标，供长按取行使用。返回 false 表示不拦截，
        // 事件继续交给 TextView 自己处理（选中、滚动、长按菜单都不受影响）。
        binding.tvLogContent.setOnTouchListener { _, ev ->
            logLastTouchY = ev.y
            false
        }

        // v1.5.0：长按日志某一行 → 只复制这一条。
        //
        // 之前 textIsSelectable 只能整段选中，想复制一条要在几十行里精确拖选，几乎不可用。
        // 这里直接按触摸落点算出对应的行号，取该行文本进剪贴板。
        //
        // 回调返回 false（不消费事件）很关键：TextView 自带的长按选词行为才能继续生效，
        // 也就是「长按=复制单行」与「长按后拖选=复制多行」两者可以并存。
        binding.tvLogContent.setOnLongClickListener { v ->
            val tv = v as TextView
            val text = tv.text
            val layout = tv.layout
            if (layout == null || text.isNullOrEmpty()) {
                false
            } else {
                // lastTouchY 由下面的 OnTouchListener 记录：OnLongClick 回调里拿不到坐标，
                // 而 TextView 也没有公开"上一次触摸位置"的 API。
                val y = (logLastTouchY - tv.totalPaddingTop + tv.scrollY).toInt().coerceAtLeast(0)
                val line = layout.getLineForVertical(y).coerceIn(0, layout.lineCount - 1)
                val start = layout.getLineStart(line)
                val end = layout.getLineEnd(line)
                val one = text.substring(start, end.coerceAtMost(text.length)).trim()
                if (one.isNotEmpty() && one != "（暂无日志）") {
                    copyToClipboard(one)
                    toast("已复制该行")
                }
                false
            }
        }

        // 实时监听：日志产生即安排一次节流刷新
        binding.cbLogAuto.isChecked = true
        logListener = L2DLog.addListener {
            if (binding.cbLogAuto.isChecked) {
                binding.root.removeCallbacks(logAutoRefresh)
                binding.root.postDelayed(logAutoRefresh, 400)
            }
        }

        refreshLogView()
    }

    /** 按当前条件取日志并渲染到文本框，同时更新统计信息 */
    private fun refreshLogView() {
        val list = L2DLog.query(
            minLevel = L2DLog.minLevel,
            moduleKeyword = logModuleFilter,
            textKeyword = logTextFilter
        )

        binding.tvLogContent.text = if (list.isEmpty()) {
            "（暂无日志）"
        } else {
            val sb = StringBuilder(list.size * 64)
            list.forEach { sb.append(it.formatLine()).append('\n') }
            sb.toString()
        }

        binding.tvLogStat.text = "${list.size} / ${L2DLog.totalCount()} 条"

        // 自动滚到底部，方便看最新一条。
        //
        // v1.4.0：仅当内层日志区**已经可见**时才滚动。
        // 旧逻辑无条件 fullScroll(FOCUS_DOWN)，会连带把外层 ScrollView 一起
        // 拽到日志面板位置 —— 用户在浏览上面的设置项时，日志一刷新就被弹走，
        // 完全没法正常操作上面的滑条和开关。
        if (binding.cbLogAuto.isChecked && isLogPanelVisible()) {
            binding.root.post {
                binding.svLog.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }

        binding.tvLogInfo.text = L2DLog.diagnostics()
    }

    /**
     * 判断日志滚动区当前是否真的显示在屏幕上。
     *
     * 用 getGlobalVisibleRect 而非 getVisibility：日志区在 ScrollView 里，
     * visibility 恒为 VISIBLE，但可能被滚到屏幕外。
     */
    private fun isLogPanelVisible(): Boolean {
        val r = android.graphics.Rect()
        if (!binding.svLog.getGlobalVisibleRect(r)) return false
        // 可见高度至少 1/3 才算「用户在看日志」，避免刚露一角就抢滚动
        return r.height() >= binding.svLog.height / 3
    }

    /**
     * 导出日志。
     *
     * 目标目录选择策略：优先写「下载」目录（用户可见、方便取出），
     * 该目录不可写时退回 App 私有目录（至少保证内容不丢）。
     */
    private fun exportLogs(json: Boolean = false) {
        val candidates = listOf(
            File("/sdcard/Download"),
            File(getExternalFilesDir(null) ?: filesDir, "logs"),
            filesDir
        )
        var lastError = ""
        for (dir in candidates) {
            val result = if (json) L2DLog.exportJsonTo(dir) else L2DLog.exportTo(dir)
            if (!result.startsWith("导出失败") && !result.startsWith("无法创建")) {
                toast(result)
                refreshLogView()
                return
            }
            lastError = result
        }
        toast(if (lastError.isEmpty()) "导出失败" else lastError)
    }

    /** 复制文本到系统剪贴板。 */
    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("l2d-log-line", text))
        } catch (t: Throwable) {
            toast("复制失败：${t.message}")
        }
    }

    /** SeekBar 监听简化封装 */
    private class SimpleSeekListener(
        private val onStop: (Int) -> Unit
    ) : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
            // 拖动过程中实时回显数值由外部通过 post 更新，这里只在松手时应用
        }

        override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}

        override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
            onStop(seekBar?.progress ?: 0)
        }
    }
}
