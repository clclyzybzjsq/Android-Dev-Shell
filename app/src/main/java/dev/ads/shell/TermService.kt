package dev.ads.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.termux.terminal.TerminalSession

/**
 * 终端宿主前台服务（specialUse）：
 *  - 托管 TerminalSession（PTY 子进程），避免 UI 退出即被杀；
 *  - coding agent 的长任务跑在会话里时保持存活。
 *
 * ⚠ Android 12+ phantom process killer：超出 32 个子进程限制时会被 SIGKILL。
 *   缓解需 adb/Shizuku：
 *     shexec settings put global settings_enable_monitor_phantom_procs false
 *   或 Android 14+ 开发者选项中关闭“子进程限制”。
 */
class TermService : Service() {

    val sessions = ArrayList<TerminalSession>()

    inner class LocalBinder : Binder() {
        val service: TermService get() = this@TermService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.fgs_running))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // 本地 REPL 服务器：终端 ads() 桥 + agent 接口
        dev.ads.shell.dsh.DshRuntime.appContext = this
        SocketRepl.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "shell 运行时", NotificationManager.IMPORTANCE_MIN)
        )
    }

    fun addSession(s: TerminalSession) {
        if (!sessions.contains(s)) sessions.add(s)
    }

    fun removeSession(s: TerminalSession) {
        sessions.remove(s)
    }

    companion object {
        const val CHANNEL_ID = "ads_runtime"
        const val NOTIFICATION_ID = 1001
    }
}
