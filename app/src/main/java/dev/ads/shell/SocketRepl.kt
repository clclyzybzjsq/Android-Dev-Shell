package dev.ads.shell

import android.content.Context

import dev.ads.shell.core.Ctx
import dev.ads.shell.core.ExitShellException
import dev.ads.shell.core.Interpreter
import dev.ads.shell.core.JValue
import dev.ads.shell.core.ProcessExternalRunner
import java.io.File
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地 socket REPL 服务器 —— 入口合并的桥 + agent 正式接口。
 *
 * - 监听 127.0.0.1 动态端口；PORT/TOKEN 写入 filesDir/ads.token（0600，仅本应用 uid 可读）
 * - 终端（mksh 子进程，同 uid）通过 .profile 里的 ads() 函数经 toybox nc 调用：
 *     ads battery
 *     ads "ls | where size gt 1000"
 * - 外部进程（coding agent）亦可连：首行发 token，此后每行一条管道，输出行流式返回，
 *   每条命令以 "ads-exit:<code>" 结尾
 */
object SocketRepl {

    private val started = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var thread: Thread? = null

    fun start(ctx: Context) {
        if (!started.compareAndSet(false, true)) return
        val tokenFile = File(ctx.filesDir, "ads.token")
        val token = tokenFile.takeIf { it.exists() }?.readText()?.trim()
            ?: SecureRandom().let { sr ->
                (0 until 32).map { "0123456789abcdef"[sr.nextInt(16)] }.joinToString("")
            }.also { tokenFile.writeText(it) }
        tokenFile.setReadable(true, true); tokenFile.setWritable(true, true)

        val svr = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        server = svr
        // ads.env 必须落在 HOME（files/home）下——.profile 以 $HOME/ads.env 引用
        val homeDir = File(ctx.filesDir, "home").apply { mkdirs() }
        File(homeDir, "ads.env").writeText(
            "ADS_PORT=${svr.localPort}\nADS_TOKEN=$token\n"
        )

        val t = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val sock = svr.accept()
                    Thread { handle(ctx, sock, token) }.apply { isDaemon = true }.start()
                }
            } catch (_: Exception) {
                // 服务关闭或异常，静默结束
            }
        }
        t.isDaemon = true
        t.name = "ads-repl-server"
        t.start()
        thread = t
    }

    private fun handle(ctx: Context, sock: Socket, token: String) {
        try {
            sock.soTimeout = 300000
            val reader = sock.getInputStream().bufferedReader()
            val writer = PrintWriter(sock.getOutputStream(), true)

            if (reader.readLine()?.trim() != token) {
                writer.println("ads-exit:2")
                return
            }

            val home = File(ctx.filesDir, "home").apply { mkdirs() }
            val appCtx = ctx.applicationContext
            val interp = Interpreter(Interpreter.defaultRegistry())
            // 每连接一个 Ctx；输出经 socket 流式返回
            val outCtx = Ctx(
                env = mutableMapOf(
                    "PATH" to "/system/bin:/system/xbin:${appCtx.applicationInfo.nativeLibraryDir}",
                    "HOME" to home.absolutePath,
                    "TERM" to "xterm-256color",
                ),
                cwd = home,
                out = { s -> writer.println(s) },
                err = { s -> writer.println(s) },
                bridge = AndroidBridgeImpl(appCtx),
                external = ProcessExternalRunner(),
                host = appCtx,
            )

            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                outCtx.lastExit = 0
                try {
                    interp.runLine(line, outCtx)
                } catch (e: ExitShellException) {
                    writer.println("ads-exit:${outCtx.lastExit}")
                    break
                } catch (e: Exception) {
                    writer.println("ads: ${e.message}")
                }
                writer.println("ads-exit:${outCtx.lastExit}")
                writer.flush()
                if (outCtx.exitRequested) break
            }
        } catch (_: Exception) {
            // 连接异常，静默关闭
        } finally {
            runCatching { sock.close() }
        }
    }
}
