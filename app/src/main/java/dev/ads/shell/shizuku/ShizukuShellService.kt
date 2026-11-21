package dev.ads.shell.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

/**
 * 以 Shizuku UserService 方式在 shell uid (2000) 下执行命令。
 */
class ShizukuShellService : IShellService.Stub() {

    override fun exec(command: String): String {
        val pb = ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true)
        val proc = pb.start()
        val out = StringBuilder()
        try {
            proc.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    out.append(line).append('\n')
                }
            }
            proc.waitFor()
        } catch (e: Exception) {
            out.append("ads-shizuku: 执行失败: ").append(e.message).append('\n')
        } finally {
            proc.destroy()
        }
        return out.toString()
    }

    override fun exit() {
        System.exit(0)
    }

    companion object {
        const val VERSION = 1
    }
}

/**
 * Shizuku 会话管理：连接检测、服务绑定与缓存。
 */
object ShizukuSession {

    @Volatile
    private var service: IShellService? = null
    private val lock = Any()

    fun ping(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Exception) { false }

    fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    fun ensureService(): IShellService? {
        if (!ping()) return null
        if (!hasPermission()) return null
        synchronized(lock) {
            service?.let { return it }
            try {
                val latch = java.util.concurrent.CountDownLatch(1)
                var bound: IShellService? = null
                val args = Shizuku.UserServiceArgs(
                    ComponentName("dev.ads.shell", "dev.ads.shell.shizuku.ShizukuShellService")
                )
                    .version(ShizukuShellService.VERSION)
                    .tag("ads")

                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        bound = binder?.let { IShellService.Stub.asInterface(it) }
                        latch.countDown()
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        synchronized(lock) { service = null }
                    }
                }
                Shizuku.bindUserService(args, conn)
                if (latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    bound?.let {
                        service = it
                        return it
                    }
                }
                return null
            } catch (_: Exception) {
                return null
            }
        }
    }

    fun exec(command: String): String {
        val s = ensureService()
            ?: throw IllegalStateException(
                if (!ping()) "Shizuku 服务未运行（无线调试启动 Shizuku 后重试）"
                else "尚未授予 ADS 使用 Shizuku 的权限"
            )
        return s.exec(command)
    }
}
