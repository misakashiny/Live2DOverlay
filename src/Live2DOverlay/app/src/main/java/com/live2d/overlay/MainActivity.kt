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
        setupPersona()
        setupLlm()
        setupSections()
        setupTune()
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
        if (key != "cycle" && key != "fx" && key != "snap") return
        // v1.7.1（P0-4）：改走专用 action。
        // 原来借道 ACTION_DEBUG_JS 注入整段 JS —— 功能虽能用，却让那条调试通道
        // 无法加 debug 守卫（一收 release 下开关就失效）。现在只传 key/on，
        // 由服务侧按白名单拼 JS，调试通道得以在 release 下彻底关闭。
        val svc = Intent(this, OverlayService::class.java).apply {
            setAction(OverlayService.ACTION_SET_PAGE_OPTION)
            putExtra("key", key)
            putExtra("on", on)
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

    /**
     * v1.7.2（AI 角色系统 P1）：角色人设面板。
     *
     * P1 只有「选择 + 应用」—— 情绪引擎(P2)/LLM(P4) 尚未接入，
     * 但切换链路已经打通，后续接引擎不需要改这里。
     */
    private fun setupPersona() {
        binding.btnPersonaPick.setOnClickListener {
            val list = PersonaStore.list(this)
            if (list.isEmpty()) {
                toast("没有可用人设（assets/personas 为空？）")
                return@setOnClickListener
            }
            val labels = list.map { p ->
                val tags = if (p.tags.isBlank()) "" else "  " + p.tags
                "${p.name}  [${p.id}·${p.source}]$tags"
            }.toTypedArray()
            android.app.AlertDialog.Builder(this)
                .setTitle("选择角色")
                .setItems(labels) { _, which ->
                    val p = list[which]
                    config.activePersonaId = p.id
                    refreshPersonaView()
                    toast("已选择：${p.name}")
                    L2DLog.i(L2DLog.Mod.AI, "已切换角色", "id=${p.id} source=${p.source}")
                    // 悬浮窗在跑就重新推送（不重载页面，避免数秒白屏）
                    if (OverlayService.isRunning) {
                        safeStartService(Intent(this, OverlayService::class.java).apply {
                            setAction(OverlayService.ACTION_REPUSH_PERSONA)
                        })
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        binding.btnAnimTest.setOnClickListener {
            if (!OverlayService.isRunning) {
                toast("请先启用悬浮窗")
                return@setOnClickListener
            }
            // 走人设的 onTap 语义槽位 —— 这条链路串起了
            // 「人设 → 槽位 → 动画 id → 页面关键帧播放」，正好用来验收 P1。
            safeStartService(Intent(this, OverlayService::class.java).apply {
                setAction(OverlayService.ACTION_PLAY_ANIMATION)
                putExtra("slot", "onTap")
            })
            toast("已请求播放，详见运行日志")
        }

        // v1.7.3（实验）：Soullink 情绪引擎开关。
        // 改动必须**重载页面** —— 引擎的脚本加载与「让位」逻辑都在页面侧，热切换做不到。
        binding.switchSoullink.isChecked = config.soullinkEnabled
        binding.switchSoullink.setOnCheckedChangeListener { _, checked ->
            config.soullinkEnabled = checked
            L2DLog.i(L2DLog.Mod.AI, "Soullink 开关变更", "enabled=$checked")
            toast(if (checked) "Soullink 已开启，正在重载页面…" else "Soullink 已关闭，正在重载页面…")
            pushLiveUpdate()
        }

        // v1.7.4：情绪引擎测试入口。
        // 验收「消息 → 分类 → VAD → 表情」闭环，也是后续接 LLM 的调试口。
        // 预设 4 条覆盖分类器的不同分支（好消息→happy / 夸夸→shy / 生气→anger / 累→tired）。
        // 之所以要预设按钮：adb 的 `input text` **打不了中文**，靠输入框没法自动化验收。
        binding.btnSoullinkSend.setOnClickListener {
            val text = binding.etSoullinkMsg.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                toast("消息为空")
            } else if (SecureKeyStore.hasKey(this)) {
                // v1.7.13：配了 LLM 就走真实对话（LLM 出语义 → 页面编译成表演）
                askLlmAndPerform(text)
            } else {
                // 没配 LLM 时退回 v1.7.7 的本地路径，保证能力不退化
                sendSoullinkMessage(text)
            }
        }
        binding.btnEmoHappy.setOnClickListener { sendSoullinkMessage("好消息！项目成功通过了") }
        binding.btnEmoShy.setOnClickListener { sendSoullinkMessage("你真可爱，好喜欢你") }
        binding.btnEmoAnger.setOnClickListener { sendSoullinkMessage("这也太离谱了，我很生气") }
        binding.btnEmoTired.setOnClickListener { sendSoullinkMessage("今天好累，压力好大") }

        refreshPersonaView()
    }

    /** v1.7.4：把一条消息投给 Soullink 引擎，用于验收「消息 → 情绪」闭环 */
    private fun sendSoullinkMessage(text: String?) {
        if (text.isNullOrBlank()) {
            toast("消息为空")
            return
        }
        if (!config.soullinkEnabled) {
            toast("请先开启 Soullink 情绪引擎")
            return
        }
        if (!OverlayService.isRunning) {
            toast("请先启用悬浮窗")
            return
        }
        safeStartService(Intent(this, OverlayService::class.java).apply {
            setAction(OverlayService.ACTION_SOULLINK_MESSAGE)
            putExtra("text", text)
        })
        toast("已投递：$text")
    }

    // ==================================================================
    // v1.7.12：LLM 凭据设置
    //
    // 安全约定（用户选择方案 A：应用内输入 + EncryptedSharedPreferences）：
    // · Key 输入框用 textPassword，且 importantForAutofill=no
    // · **只回填掩码，绝不回填 Key 原文** —— 避免 Key 出现在界面/截屏里
    // · 「输入框留空 = 不改动已存的 Key」—— 用户只改 baseUrl/model 时不会把 Key 清掉
    // · 保存后立刻清空输入框，不留明文
    // ==================================================================

    /** v1.7.13：对话历史（内存态，只留最近 6 轮 = 12 条；进程结束即丢） */
    private val llmHistory = mutableListOf<Pair<String, String>>()

    /**
     * v1.7.13：走真实 LLM 的一轮对话 —— **LLM 只出语义，页面把它编译成有时长的表演**。
     *
     * 为什么是「LLM 只出语义」（docs/34 调研结论）：
     * `SpeechPerformancePlan` 的手势模板 / 曲线 / 通道幅度**全部由 engine 本地规则
     * + seededRandom 决定**；LLM 能注入的只有 emotion/intensity/confidence/
     * durationMs/semanticCues/deliveryHints 这几个字段。
     *
     * 历史只留最近 6 轮（与 `LlmClient` 的 `takeLast(6)` 一致），避免无谓 token 消耗。
     */
    private fun askLlmAndPerform(userText: String) {
        if (!config.soullinkEnabled) {
            // ★ 必须记日志：只 toast 的话「对话没反应」在日志里查不到原因（踩过）
            L2DLog.w(L2DLog.Mod.AI, "对话未发起：Soullink 未开启")
            toast("请先开启 Soullink 情绪引擎")
            return
        }
        if (!OverlayService.isRunning) {
            L2DLog.w(L2DLog.Mod.AI, "对话未发起：悬浮窗未运行")
            toast("请先启用悬浮窗")
            return
        }
        binding.tvLlmStatus.text = "思考中…"
        LlmClient.converse(this, userText, llmHistory) { sem, err ->
            runOnUiThread {
                if (sem == null) {
                    binding.tvLlmStatus.text = "❌ $err"
                    toast("LLM 失败：$err")
                    return@runOnUiThread
                }
                llmHistory.add("user" to userText)
                llmHistory.add("assistant" to sem.reply)
                while (llmHistory.size > 12) llmHistory.removeAt(0)

                binding.tvLlmStatus.text = "角色：${sem.reply}\n" +
                        "（${sem.emotion} · 强度${"%.2f".format(sem.intensity)} · " +
                        "时长${sem.durationMs}ms · 往返${sem.latencyMs}ms）"

                val json = org.json.JSONObject().apply {
                    put("emotion", sem.emotion)
                    put("intensity", sem.intensity)
                    put("confidence", sem.confidence)
                    put("durationMs", sem.durationMs)
                    put("semanticCues", org.json.JSONArray(sem.semanticCues))
                    put("deliveryHints", org.json.JSONArray(sem.deliveryHints))
                    put("reply", sem.reply)
                }.toString()
                safeStartService(Intent(this, OverlayService::class.java).apply {
                    setAction(OverlayService.ACTION_SOULLINK_PERFORM)
                    putExtra("json", json)
                })
                binding.etSoullinkMsg.text?.clear()

                // v1.7.16：TTS —— 合成语音后投给页面（页面负责播放 + WebAudio 口型）
                // 放在表演之后：先让动作起来，语音到了再叠加上去，体感更快。
                // v1.7.18：受「对话时自动朗读」开关控制 —— 关掉后表演与字幕照常，只是不出声。
                if (config.voiceEnabled && SecureKeyStore.hasTts(this)) {
                    TtsClient.synthesize(this, sem.reply) { speech, terr ->
                        runOnUiThread {
                            if (speech == null) {
                                // TTS 失败不该影响已经播出去的表演与字幕
                                L2DLog.w(L2DLog.Mod.AI, "TTS 未播放", "err=$terr")
                                toast("语音合成失败（表演与字幕已播）")
                            } else {
                                // ★ v1.7.17：这里必须 try/catch + 记日志。
                                //   踩过的坑：data URL 有 31 万字符，而 Java String 是 UTF-16，
                                //   塞进 Intent extra 时按 ~62 万字节算，顶到 Binder 1MB 事务上限，
                                //   safeStartService 抛异常被吞掉 —— 服务侧一条日志都没有，
                                //   表现为「TTS 完成但语音没播」，两端都查不到原因。
                                try {
                                    safeStartService(Intent(this, OverlayService::class.java).apply {
                                        setAction(OverlayService.ACTION_SOULLINK_SPEAK)
                                        putExtra("dataUrl", speech.dataUrl)
                                    })
                                    L2DLog.i(L2DLog.Mod.AI, "语音指令已发出",
                                        "dataUrl=${speech.dataUrl.length}字符")
                                } catch (t: Throwable) {
                                    L2DLog.e(L2DLog.Mod.AI, "语音投递失败",
                                        "dataUrl=${speech.dataUrl.length}字符 " +
                                                "err=${t.javaClass.simpleName}: ${t.message}", t)
                                    toast("语音投递失败（数据过大），表演与字幕已播")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ==================================================================
    // v1.7.18：UI 重做 —— 顶部分栏
    //
    // 背景：功能越加越多，界面变成 9 个区块、69 个控件平铺在一个滚动列表里，
    // 找个开关要滚半天。改成 4 个 Tab，切换时只切 visibility。
    //
    // 为什么不用 Fragment：这些区块共享同一个 Activity 的 binding 与状态，
    // 拆 Fragment 要引入 ViewModel/通信，收益不抵成本。切 visibility 足够。

    // ==================================================================
    // v1.7.18：分段控件 —— 用 7 个卡片容器的 visibility 分组
    //
    // 为什么不用 TabLayout：它必须放在 ScrollView **外面**，会引入一层外层包裹，
    // 而上次正是那层包裹踩了命名空间错误、且脚本插入的 </LinearLayout> 关错了层级
    // （XML 合法、编译通过、无崩溃，但运行时切不过去）。
    // 这次**不做任何结构改动**：只给已有的 7 个卡片容器加 id，直接切它们的 visibility。
    // ==================================================================

    private fun setupSections() {
        val groups = listOf(
            listOf(binding.segStatus, binding.secStatus, binding.secModel),
            listOf(binding.segCharacter, binding.secPersona),
            listOf(binding.segDisplay, binding.secDisplay, binding.secSwitch, binding.secFeedback),
            listOf(binding.segLog, binding.secLog)
        )
        fun apply(active: Int) {
            groups.forEachIndexed { gi, g ->
                val on = gi == active
                g.drop(1).forEach { v ->
                    v.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
                }
                g.first().alpha = if (on) 1f else 0.45f
            }
        }
        groups.forEachIndexed { gi, g -> g.first().setOnClickListener { apply(gi) } }
        apply(0)
        L2DLog.i(L2DLog.Mod.UI, "分段控件已就绪", "sections=4 cards=7")
    }

    /** v1.7.23：调参界面 —— 拖动即时生效，并持久化（重载页面后靠 URL 参数恢复） */
    private fun setupTune() {
        // 读回已存的调参值（JSON 串）
        fun loadMap(): MutableMap<String, Float> {
            val out = mutableMapOf<String, Float>()
            val raw = config.tuneJson
            if (raw.isBlank()) return out
            try {
                val o = org.json.JSONObject(raw)
                for (k in o.keys()) out[k] = o.getDouble(k).toFloat()
            } catch (t: Throwable) { L2DLog.w(L2DLog.Mod.UI, "调参值解析失败，用默认", "err=${t.message}") }
            return out
        }
        val cur = loadMap()
        fun push() {
            val o = org.json.JSONObject()
            for ((k, v) in cur) o.put(k, v.toDouble())
            val json = o.toString()
            safeStartService(Intent(this, OverlayService::class.java).apply {
                setAction(OverlayService.ACTION_SOULLINK_TUNE)
                putExtra("json", json)
            })
        }
        fun bind(seek: android.widget.SeekBar, tv: android.widget.TextView,
                 key: String, lo: Float, hi: Float, def: Float) {
            seek.max = 100
            val v0 = (cur[key] ?: def).coerceIn(lo, hi)
            seek.progress = (((v0 - lo) / (hi - lo)) * 100f).toInt().coerceIn(0, 100)
            fun show() { tv.text = "%.2f".format(lo + (hi - lo) * seek.progress / 100f) }
            show()
            seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    show()
                    if (!fromUser) return
                    cur[key] = lo + (hi - lo) * p / 100f
                    push()
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        bind(binding.seekTuneParam, binding.tvTuneParam, "parameterGain", 0.4f, 5f, 1.45f)
        bind(binding.seekTuneBody, binding.tvTuneBody, "bodyMotionGain", 0f, 4f, 1.25f)
        bind(binding.seekTuneIdle, binding.tvTuneIdle, "idleActionGain", 0f, 2f, 1f)
        bind(binding.seekTuneMicro, binding.tvTuneMicro, "microMotionGain", 0f, 2f, 1f)
        // v1.7.25：表情增益（页面侧乘在表情类参数上）
        bind(binding.seekTuneExpr, binding.tvTuneExpr, "expressionGain", 0.5f, 4f, 1f)
        // v1.7.26：脸部表情（页面侧自己驱动的眼笑/眉/眼珠）
        bind(binding.seekTuneFace, binding.tvTuneFace, "faceGain", 0f, 3f, 1f)
        // v1.7.25：复制当前值 —— 便于把调好的数值交给开发者固化
        binding.btnTuneCopy.setOnClickListener {
            val json = config.tuneJson.ifBlank { "{}" }
            copyToClipboard(json)
            toast("已复制调参值，详见运行日志")
        }
        // v1.7.25：一键填入推荐值（我按实测调的：不撞顶 + 表情放大）
        binding.btnTuneRecommend.setOnClickListener {
            val rec = org.json.JSONObject()
                .put("parameterGain", 3.0)
                .put("bodyMotionGain", 3.5)
                .put("idleActionGain", 1.9)
                .put("microMotionGain", 1.9)
                .put("expressionGain", 2.5)
                .put("faceGain", 1.6)
            cur.clear()
            for (k in rec.keys()) cur[k] = rec.getDouble(k).toFloat()
            push()
            // 回填滑条显示
            binding.seekTuneParam.progress = (((3.0f - 0.4f) / (5f - 0.4f)) * 100f).toInt()
            binding.seekTuneBody.progress = (((3.5f - 0f) / (4f - 0f)) * 100f).toInt()
            binding.seekTuneIdle.progress = (((1.9f - 0f) / (2f - 0f)) * 100f).toInt()
            binding.seekTuneMicro.progress = (((1.9f - 0f) / (2f - 0f)) * 100f).toInt()
            binding.seekTuneExpr.progress = (((2.5f - 0.5f) / (4f - 0.5f)) * 100f).toInt()
            binding.seekTuneFace.progress = (((1.6f - 0f) / (3f - 0f)) * 100f).toInt()
            toast("已填入推荐值")
        }
        binding.btnTuneReset.setOnClickListener {
            cur.clear()
            push()
            toast("已恢复默认（重开悬浮窗后完全生效）")
        }
        L2DLog.i(L2DLog.Mod.UI, "调参界面已就绪", "已存参数=" + cur.keys.joinToString("/"))
    }

    private fun setupLlm() {
        if (!SecureKeyStore.available(this)) {
            binding.tvLlmStatus.text = "⚠️ 加密存储不可用（Keystore 异常），无法保存凭据"
            binding.btnLlmSave.isEnabled = false
            binding.btnLlmTest.isEnabled = false
            return
        }
        importLlmCredsIfDebug()
        refreshLlmView()
        debugSayIfRequested()

        binding.btnLlmSave.setOnClickListener {
            val typed = binding.etLlmKey.text?.toString()?.trim().orEmpty()
            val baseUrl = binding.etLlmBaseUrl.text?.toString()?.trim().orEmpty()
            val model = binding.etLlmModel.text?.toString()?.trim().orEmpty()
            val newKey = if (typed.isEmpty()) null else typed   // 空 = 不改动
            val ok = SecureKeyStore.save(
                this,
                newKey,
                baseUrl.ifEmpty { SecureKeyStore.DEFAULT_BASE_URL },
                model.ifEmpty { SecureKeyStore.DEFAULT_MODEL }
            )
            // v1.7.17：同一个按钮顺带保存 TTS 配置（方案 A：一个百炼 Key 管两者）
            val ttsModel = binding.etTtsModel.text?.toString()?.trim().orEmpty()
            val ttsVoice = binding.etTtsVoice.text?.toString()?.trim().orEmpty()
            val okTts = SecureKeyStore.saveTts(
                this,
                SecureKeyStore.ttsBaseUrl(this),
                ttsModel.ifEmpty { TtsClient.DEFAULT_MODEL },
                ttsVoice.ifEmpty { TtsClient.DEFAULT_VOICE }
            )
            toast(if (ok && okTts) "凭据与语音配置已保存" else "保存失败")
            binding.etLlmKey.text?.clear()   // 保存后立刻清掉，不留明文
            refreshLlmView()
        }

        // v1.7.18：对话时自动朗读（TTS）总开关
        binding.switchVoice.isChecked = config.voiceEnabled
        binding.switchVoice.setOnCheckedChangeListener { _, checked ->
            config.voiceEnabled = checked
            L2DLog.i(L2DLog.Mod.AI, "语音朗读开关变更", "enabled=$checked")
            toast(if (checked) "对话时将自动朗读" else "已静音（表演与字幕照常）")
        }

        // v1.7.17：音色快捷选择（用户挑的三个）
        binding.btnVoiceSerena.setOnClickListener { binding.etTtsVoice.setText("Serena") }
        binding.btnVoiceMomo.setOnClickListener { binding.etTtsVoice.setText("Momo") }
        binding.btnVoiceBella.setOnClickListener { binding.etTtsVoice.setText("Bella") }

        // v1.7.17：试听 —— 合成一句固定话术并投给页面（验证「TTS → 语音 → 口型」）
        binding.btnTtsSpeak.setOnClickListener {
            if (!SecureKeyStore.hasKey(this)) {
                toast("请先保存 API Key")
                return@setOnClickListener
            }
            // 先落盘当前音色，否则改完不点保存就试听会用旧的
            val v = binding.etTtsVoice.text?.toString()?.trim().orEmpty()
            val m = binding.etTtsModel.text?.toString()?.trim().orEmpty()
            SecureKeyStore.saveTts(
                this, SecureKeyStore.ttsBaseUrl(this),
                m.ifEmpty { TtsClient.DEFAULT_MODEL },
                v.ifEmpty { TtsClient.DEFAULT_VOICE }
            )
            binding.tvLlmStatus.text = "语音合成中…（最长 60 秒）"
            TtsClient.synthesize(this, "你好呀，我是初音未来，很高兴见到你。") { speech, err ->
                runOnUiThread {
                    if (speech == null) {
                        binding.tvLlmStatus.text = "❌ TTS 失败：$err"
                        toast("语音合成失败，详见运行日志")
                    } else {
                        binding.tvLlmStatus.text =
                            "✅ 语音 ${speech.bytes}B · ${speech.latencyMs}ms · ${speech.mime}"
                        safeStartService(Intent(this, OverlayService::class.java).apply {
                            setAction(OverlayService.ACTION_SOULLINK_SPEAK)
                            putExtra("dataUrl", speech.dataUrl)
                        })
                    }
                }
            }
        }

        binding.btnLlmClear.setOnClickListener {
            SecureKeyStore.clear(this)
            binding.etLlmKey.text?.clear()
            refreshLlmView()
            toast("凭据已清除")
        }

        binding.btnLlmTest.setOnClickListener {
            if (!SecureKeyStore.hasKey(this)) {
                toast("请先保存 API Key")
                return@setOnClickListener
            }
            binding.tvLlmStatus.text = "测试中…（最长 45 秒）"
            LlmClient.converse(this, "你好，请用一句话打个招呼。", emptyList()) { sem, err ->
                runOnUiThread {
                    if (sem != null) {
                        binding.tvLlmStatus.text =
                            "✅ 连通 · ${sem.latencyMs}ms · emotion=${sem.emotion} · " +
                                    "durationMs=${sem.durationMs}"
                        toast("LLM 连通：${sem.reply.take(30)}")
                    } else {
                        binding.tvLlmStatus.text = "❌ 失败：$err"
                        toast("LLM 测试失败，详见运行日志")
                    }
                }
            }
        }
    }

    /**
     * v1.7.13 · **仅 debug 构建**：用文件触发一轮真实对话，便于自动化验证。
     *
     * 为什么需要：本机 MIUI 的 UI 自动化点不到「发送」按钮（dump 截断 + ScrollView
     * 不响应合成 swipe），而「LLM → ACTION_SOULLINK_PERFORM → planner.plan →
     * startSpeechPerformance」这条链必须能自动化验证。
     *
     * 安全边界同 [importLlmCredsIfDebug]：`BuildConfig.DEBUG` 守卫 + 读完即删 + 不记原文。
     * 文件内容就是「用户说的话」（纯文本）。
     */
    private fun debugSayIfRequested() {
        if (!BuildConfig.DEBUG) return
        val f = java.io.File("/sdcard/Live2DModels/llm-say.txt")
        if (!f.isFile) return
        val text = try {
            f.readText().trim()
        } catch (t: Throwable) {
            L2DLog.e(L2DLog.Mod.AI, "读取 llm-say.txt 失败", "err=${t.javaClass.simpleName}", t)
            ""
        } finally {
            val deleted = f.delete()
            L2DLog.i(L2DLog.Mod.AI, "llm-say.txt 已清理", "deleted=$deleted")
        }
        if (text.isEmpty()) {
            L2DLog.w(L2DLog.Mod.AI, "llm-say.txt 内容为空，忽略")
            return
        }
        L2DLog.i(L2DLog.Mod.AI, "调试触发一轮对话（DEBUG）", "chars=${text.length}")
        // 悬浮窗没开就先开 —— 否则 askLlmAndPerform 会因 isRunning=false 直接返回
        if (!OverlayService.isRunning) {
            L2DLog.i(L2DLog.Mod.AI, "调试钩子：悬浮窗未运行，先启动")
            startOverlay()
        }
        // ★ 轮询等待，不用固定延时。
        //   踩过的坑：原来写 postDelayed(8000)，但服务启动要建两个窗口 +
        //   WebView 加载页面 + 加载模型，实测 12~18 秒 → 8 秒后仍 isRunning=false，
        //   对话被前置检查挡掉（日志只留一句「对话未发起：悬浮窗未运行」）。
        waitForOverlayThenSay(text, 0)
    }

    /** 每 2 秒检查一次悬浮窗是否就绪，最多等 40 秒，就绪后再发起对话。 */
    private fun waitForOverlayThenSay(text: String, tries: Int) {
        if (OverlayService.isRunning) {
            L2DLog.i(L2DLog.Mod.AI, "悬浮窗已就绪，发起对话", "等待轮次=$tries")
            askLlmAndPerform(text)
            return
        }
        if (tries >= 20) {
            L2DLog.w(L2DLog.Mod.AI, "等待悬浮窗超时，放弃本次调试对话",
                "tries=$tries 已等=${tries * 2}秒")
            return
        }
        binding.root.postDelayed({ waitForOverlayThenSay(text, tries + 1) }, 2000)
    }

    /**
     * v1.7.12 · **仅 debug 构建**：从 sdcard 文件导入 LLM 凭据。
     *
     * 为什么需要：本机 MIUI 的 UI 自动化不可靠（uiautomator dump 截断 + ScrollView
     * 不响应合成 swipe，实测 20 步滚动可见控件集合完全不变），无法自动把 Key
     * 填进 `et_llm_key`；而「测试连接」这条链路必须能自动化验证。
     *
     * 安全边界（与 P0-4 的 `ACTION_DEBUG_JS` 收口做法一致）：
     * - `BuildConfig.DEBUG` 守卫 —— **release 构建里这段代码不会执行**
     * - 只在**尚未配置**时导入，不会覆盖用户手填的
     * - 导入后**立即删除**明文文件（push 由调用方做，清理由本函数做）
     * - 日志只记掩码，不记原文
     *
     * 文件格式（3 行；`#` 开头为注释行）：
     * ```
     * <apiKey>
     * <baseUrl>
     * <model>
     * ```
     */
    private fun importLlmCredsIfDebug() {
        if (!BuildConfig.DEBUG) return
        // 不检查 hasKey —— **文件存在本身就是守卫**（导入后立即删除，天然只跑一次）。
        // 保留 hasKey 检查会导致「想重新导入时必须先清凭据」，反而更难用。
        val f = java.io.File("/sdcard/Live2DModels/llm-creds.txt")
        if (!f.isFile) return
        try {
            val lines = f.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
            if (lines.size < 3) {
                L2DLog.w(L2DLog.Mod.AI, "凭据文件格式不对（需 3 行）", "lines=${lines.size}")
            } else {
                val ok = SecureKeyStore.save(this, lines[0], lines[1], lines[2])
                L2DLog.w(L2DLog.Mod.AI, "已从文件导入 LLM 凭据（DEBUG）",
                    "ok=$ok key=${SecureKeyStore.masked(lines[0])} " +
                            "base=${lines[1]} model=${lines[2]}")
                // DEBUG：导入后立刻自测一次，验证「凭据 → HTTP → 解析」全链路。
                // 之所以放在这里而不是靠点按钮：本机 UI 自动化点不到「测试连接」。
                if (ok) {
                    L2DLog.i(L2DLog.Mod.AI, "LLM 自测开始（DEBUG）", "由导入路径触发")
                    LlmClient.converse(this, "你好，请用一句话打个招呼。", emptyList()) { sem, err ->
                        runOnUiThread {
                            if (sem != null) {
                                L2DLog.i(L2DLog.Mod.AI, "LLM 自测通过",
                                    "耗时=${sem.latencyMs}ms emotion=${sem.emotion} " +
                                            "intensity=${"%.2f".format(sem.intensity)} " +
                                            "durationMs=${sem.durationMs} " +
                                            "cues=${sem.semanticCues.joinToString("/")} " +
                                            "hints=${sem.deliveryHints.joinToString("/")} " +
                                            "reply=${sem.reply.take(60)}")
                            } else {
                                L2DLog.e(L2DLog.Mod.AI, "LLM 自测失败", "err=$err")
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            L2DLog.e(L2DLog.Mod.AI, "导入凭据失败", "err=${t.javaClass.simpleName}", t)
        } finally {
            // ★ 无论如何都删掉明文文件
            val deleted = f.delete()
            L2DLog.i(L2DLog.Mod.AI, "明文凭据文件已清理",
                "deleted=$deleted path=${f.absolutePath}")
        }
    }

    /** 刷新 LLM 状态行（**只显示掩码**，不显示 Key 原文） */    private fun refreshLlmView() {
        val key = SecureKeyStore.apiKey(this)
        binding.tvLlmStatus.text = if (key.isNullOrBlank()) {
            "未配置"
        } else {
            "已配置 · key=${SecureKeyStore.masked(key)} · ${SecureKeyStore.model(this)}"
        }
        // 只在为空时回填默认值，避免覆盖用户已改的
        if (binding.etLlmBaseUrl.text.isNullOrBlank()) {
            binding.etLlmBaseUrl.setText(SecureKeyStore.baseUrl(this))
        }
        if (binding.etLlmModel.text.isNullOrBlank()) {
            binding.etLlmModel.setText(SecureKeyStore.model(this))
        }
        // v1.7.17：TTS 字段同样只在为空时回填
        if (binding.etTtsModel.text.isNullOrBlank()) {
            binding.etTtsModel.setText(SecureKeyStore.ttsModel(this))
        }
        if (binding.etTtsVoice.text.isNullOrBlank()) {
            binding.etTtsVoice.setText(SecureKeyStore.ttsVoice(this))
        }
    }

    /** 刷新「当前角色」那一行 */
    private fun refreshPersonaView() {
        val id = config.activePersonaId
        val p = PersonaStore.load(this, id)
        binding.tvPersona.text = if (p == null) {
            "当前角色：$id（加载失败，页面沿用内置行为）"
        } else {
            val src = PersonaStore.list(this).firstOrNull { it.id == id }?.source ?: "?"
            val traits = if (p.traits.isEmpty()) "" else
                "　性格 " + p.traits.entries.joinToString(" ") { "${it.key}=${it.value}" }
            "当前角色：${p.name}（$id·$src）$traits"
        }
    }

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
            // v1.7.2：复制是「看不见的操作」—— 剪贴板内容无法从 adb 安全读取
            // （`service call clipboard` 有误触 clearPrimaryClip 的风险）。
            // 这里落一条日志记录字符数/行数/开头，既用于验收「长按复制单条」，
            // 也便于排查「复制错了行」。
            L2DLog.i(
                L2DLog.Mod.UI, "已复制到剪贴板",
                "chars=${text.length} lines=${text.count { it == '\n' } + 1} " +
                        "preview=${text.take(48).replace('\n', '|')}"
            )
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
