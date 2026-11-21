package dev.ads.shell

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.ads.shell.core.AndroidBridge
import dev.ads.shell.core.JValue
import dev.ads.shell.shizuku.ShizukuSession

/** Android 能力桥实现：把 PackageManager/Clipboard/Notification 等 Android API 暴露给 cmdlet */
class AndroidBridgeImpl(private val app: Context) : AndroidBridge {

    override fun clipboardGet(): String? {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.getItemAt(0)?.coerceToText(app)?.toString()
    }

    override fun clipboardSet(text: String) {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ads", text))
    }

    override fun notifyUser(title: String, body: String): Boolean {
        return try {
            val notification = NotificationCompat.Builder(app, "ads_notify")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .build()
            val nm = NotificationManagerCompat.from(app)
            val channel = android.app.NotificationChannel(
                "ads_notify", "ADS 通知", android.app.NotificationManager.IMPORTANCE_DEFAULT
            )
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .createNotificationChannel(channel)
            nm.notify((System.currentTimeMillis() and 0x7fffffff).toInt(), notification)
            true
        } catch (_: Exception) { false }
    }

    override fun openUri(uri: String): Boolean {
        return try {
            // 包名 → 直接启动应用；否则按 URL/URI 打开
            if (!uri.contains("://") && uri.matches(Regex("[\\w.]+"))) {
                val i = app.packageManager.getLaunchIntentForPackage(uri)
                if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); app.startActivity(i); return true }
                return false
            }
            val u = if (uri.contains("://")) Uri.parse(uri) else Uri.parse("https://$uri")
            val i = Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(i); true
        } catch (_: Exception) { false }
    }

    override fun listApps(): List<Pair<String, String>> {
        val pm = app.packageManager
        val out = ArrayList<Pair<String, String>>()
        try {
            for (pkg in pm.getInstalledPackages(0)) {
                out.add((pkg.applicationInfo?.loadLabel(pm)?.toString() ?: "") to pkg.packageName)
            }
        } catch (_: Exception) {}
        return out
    }

    override fun deviceInfo(): LinkedHashMap<String, JValue> {
        val m = linkedMapOf<String, JValue>(
            "model" to JValue.JStr(Build.MODEL),
            "brand" to JValue.JStr(Build.BRAND),
            "device" to JValue.JStr(Build.DEVICE),
            "androidVersion" to JValue.JStr(Build.VERSION.RELEASE),
            "sdkInt" to JValue.JLong(Build.VERSION.SDK_INT.toLong()),
            "abi" to JValue.JStr(Build.SUPPORTED_ABIS.firstOrNull() ?: ""),
            "bootTime" to JValue.JLong(Build.TIME),
        )
        try {
            m["hostname"] = JValue.JStr(Settings.Global.getString(app.contentResolver, "device_name") ?: "")
        } catch (_: Exception) {}
        return m
    }

    override fun battery(): LinkedHashMap<String, JValue> {
        val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = app.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val charging = status?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING
        val plugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return linkedMapOf(
            "percent" to JValue.JLong(pct.toLong()),
            "charging" to JValue.JBool(charging),
            "plugged" to JValue.JLong(plugged.toLong()),
        )
    }

    override fun shizukuConnected(): Boolean =
        ShizukuSession.ping() && ShizukuSession.hasPermission()

    override fun shizukuExec(command: String): String = ShizukuSession.exec(command)
}
