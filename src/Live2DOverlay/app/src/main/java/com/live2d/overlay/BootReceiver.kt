package com.live2d.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * 开机自启接收器。
 *
 * 车机/平板场景下设备长时间不重启，但首次部署后通常只开机一次，
 * 因此自启是保证「开机即见模型」的必要环节。
 *
 * 守卫条件：
 *  1. 用户已在控制台开启自启开关；
 *  2. 悬浮窗权限仍然有效（权限可能被系统或用户回收）。
 * 任一不满足则静默退出，不弹任何界面。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        val config = OverlayConfig(context)
        if (!config.autoStart) {
            Log.d(TAG, "auto start disabled, skip")
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "overlay permission missing, skip auto start")
            return
        }
        if (config.modelPath.isEmpty()) {
            Log.w(TAG, "no model configured, skip auto start")
            return
        }

        Log.i(TAG, "boot completed, starting overlay service")
        val svc = Intent(context, OverlayService::class.java).apply {
            setAction(OverlayService.ACTION_START)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        } catch (t: Throwable) {
            // Android 12+ 后台启动前台服务受限，失败时留待用户手动开启
            Log.e(TAG, "start overlay service from boot failed", t)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
