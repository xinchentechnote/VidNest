package com.vidnest.app.data.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import com.vidnest.app.ServiceLocator
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 后台扫描前台服务（docs/permissions-and-media-scanning.md §2.2）：
 * - 所有扫描触发（冷启动/手动重扫/MediaStore 变更）统一经本服务执行，
 *   应用退到后台或用户离开页面时扫描继续，不被界面生命周期中断；
 * - 前台通知展示进度（Android 13+ 未授予通知权限时通知不显示，但服务照常运行）；
 * - 单飞与状态机在 ScanManager，本服务只负责提权保活与自停。
 */
class ScanService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val auto = intent?.getBooleanExtra(EXTRA_AUTO, false) ?: false
        startAsForeground()

        ServiceLocator.scanManager.startScan(auto = auto)

        // 扫描结束（完成或失败）即自停。
        // 先等本轮离开 Idle（避免吃到上一次扫描的终态），再等终态。
        scope.launch {
            val manager = ServiceLocator.scanManager
            manager.state.first { it !is ScanManager.State.Idle }
            manager.state.first { state ->
                state is ScanManager.State.Completed || state is ScanManager.State.Failed
            }
            stopForegroundCompat()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = buildNotification(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "媒体库扫描", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(foundSoFar: Int): Notification {
        val state = ServiceLocator.scanManager.state.value
        val found = (state as? ScanManager.State.Scanning)?.foundSoFar ?: foundSoFar
        val text = when (state) {
            is ScanManager.State.Completed -> "扫描完成：发现 ${state.result.totalFound} 个视频"
            is ScanManager.State.Failed -> "扫描出现问题：${state.reason}"
            is ScanManager.State.Scanning -> "已发现 $found 个视频…"
            else -> "准备扫描…"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("正在扫描视频库")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "vidnest_scan"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_AUTO = "auto"

        /**
         * 触发后台扫描。应用在前台或进程活跃时安全；
         * 若系统禁止后台启动前台服务（Android 12+ 后台限制），回退为进程内直接扫描。
         */
        fun start(context: Context, auto: Boolean) {
            val intent = Intent(context, ScanService::class.java).putExtra(EXTRA_AUTO, auto)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: IllegalStateException) {
                // 后台启动受限：进程仍活跃时退化为应用内扫描（无通知保活）
                ServiceLocator.scanManager.startScan(auto = auto)
            }
        }
    }
}
