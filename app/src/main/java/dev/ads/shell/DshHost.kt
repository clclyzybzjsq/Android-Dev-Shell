package dev.ads.shell

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * dsh 常驻进程宿主：spawn libnode.so + dsh bin.js（sdk-minimal JSON-RPC 服务），
 * stdin 写入 / stdout 逐行回调。WebView UI 经此与 harness 内核通信。
 */
object DshHost {

    private var proc: Process? = null
    private var writer: PrintWriter? = null
    private val running = AtomicBoolean(false)
    private var lineSink: ((String) -> Unit)? = null
    private var exitSink: ((Int) -> Unit)? = null
    private var urlSink: ((String) -> Unit)? = null
    private var apiKey: String? = null
    private var mode: String = "sdk-minimal"

    fun setApiKey(key: String?) { apiKey = key }

    fun isRunning(): Boolean = running.get() && proc?.isAlive == true

    fun setSinks(onLine: (String) -> Unit, onExit: (Int) -> Unit) {
        lineSink = onLine
        exitSink = onExit
    }

    /** web 模式下：官方 UI 就绪时回调 http://127.0.0.1:port/?token=... */
    fun setUrlSink(onUrl: (String) -> Unit) { urlSink = onUrl }

    @Synchronized
    fun start(ctx: Context, profile: String = "sdk-minimal") {
        if (isRunning()) return
        mode = profile
        val appCtx = ctx.applicationContext
        val node = File(appCtx.applicationInfo.nativeLibraryDir, "libnode.so")
        val binJs = File(appCtx.filesDir, "dsh-runtime/node_modules/@deepseek-ai/dsh/lib/bin.js")
        val dshHome = File(appCtx.filesDir, "dsh-home").apply { mkdirs() }
        val workDir = File(dshHome, "workspace").apply { mkdirs() }

        val argv = mutableListOf(node.absolutePath, binJs.absolutePath, "--profile", profile)
        if (profile == "web") argv.add("--no-open")

        val envList = mutableListOf(
            "HOME=${dshHome.absolutePath}",
            "PATH=/system/bin:/system/xbin:${appCtx.applicationInfo.nativeLibraryDir}",
            "TERM=xterm-256color",
            "TMPDIR=${appCtx.cacheDir.absolutePath}",
            "LANG=en_US.UTF-8",
            "DSH_HOME=${dshHome.absolutePath}",
            "DSH_PREFIX=${dshHome.absolutePath}",
            "NARB_DISABLE_NATIVE_CACHE=1",
            "LD_LIBRARY_PATH=${appCtx.applicationInfo.nativeLibraryDir}",
            "ANDROID_DATA=/data",
        )
        apiKey?.takeIf { it.isNotBlank() }?.let { envList.add("DEEPSEEK_API_KEY=$it") }

        val pb = ProcessBuilder(argv)
            .directory(workDir)
            .redirectErrorStream(false)
        pb.environment().clear()
        pb.environment().putAll(envList.associate { val i = it.indexOf('='); it.substring(0, i) to it.substring(i + 1) })

        val p = pb.start()
        proc = p
        running.set(true)
        writer = PrintWriter(p.outputStream.bufferedWriter(), true)

        Thread {
            try {
                val r = BufferedReader(InputStreamReader(p.inputStream))
                while (running.get()) {
                    val line = r.readLine() ?: break
                    if (mode == "web") {
                        // dsh web: http://127.0.0.1:3080/?token=...
                        val m = Regex("""http://[^\s]*token=\S+""").find(line)
                        if (m != null) urlSink?.invoke(m.value)
                    }
                    lineSink?.invoke(line)
                }
            } catch (_: Exception) {}
            val code = p.waitFor()
            running.set(false)
            exitSink?.invoke(code)
        }.apply { isDaemon = true; name = "dsh-stdout" }.start()

        Thread {
            try {
                val r = BufferedReader(InputStreamReader(p.errorStream))
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    lineSink?.invoke(line ?: "")
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; name = "dsh-stderr" }.start()
    }

    @Synchronized
    fun send(line: String): Boolean {
        val w = writer ?: return false
        return try {
            w.println(line)
            w.flush()
            true
        } catch (_: Exception) { false }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { writer?.close() }
        runCatching { proc?.destroy() }
        proc = null
        writer = null
    }
}
