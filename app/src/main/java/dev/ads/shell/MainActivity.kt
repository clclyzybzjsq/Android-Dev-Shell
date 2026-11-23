package dev.ads.shell

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

/**
 * 交互式终端：全屏 TerminalView，背后是常驻 TermService 的 mksh PTY 会话。
 * 顶部工具条可进入“对象管道控制台”。
 */
class MainActivity : AppCompatActivity(), TerminalSessionClient {

    private var termService: TermService? = null
    private var session: TerminalSession? = null
    private lateinit var terminalView: TerminalView
    private lateinit var statusText: TextView
    private var ctrlDown = false
    private var altDown = false

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            termService = (binder as TermService.LocalBinder).service
            ensureSession()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            termService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101418.toInt())
        }

        // 顶栏
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val title = TextView(this).apply {
            text = " ADS"
            setTextColor(0xFF9CDCFE.toInt())
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(24, 24, 0, 24)
            }
        }
        bar.addView(title)
        val pipeBtn = TextView(this).apply {
            text = "⌗ 管道控制台 "
            setTextColor(0xFF4EC9B0.toInt())
            textSize = 15f
            setPadding(0, 24, 24, 24)
            setOnClickListener { startActivity(Intent(this@MainActivity, PipeConsoleActivity::class.java)) }
        }
        bar.addView(pipeBtn)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        terminalView = TerminalView(this, null)
        terminalView.setTextSize(30)
        terminalView.setBackgroundColor(0xFF101418.toInt())
        terminalView.setTerminalViewClient(Client())
        terminalView.isFocusableInTouchMode = true
        root.addView(terminalView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // 软键盘/adb 注入的 ENTER 必须落到终端而非其他可点击视图
        terminalView.requestFocus()

        root.addView(buildExtraKeys(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        statusText = TextView(this).apply {
            setTextColor(0xFF808080.toInt())
            textSize = 12f
            setPadding(24, 8, 24, 12)
        }
        root.addView(statusText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        bindService(Intent(this, TermService::class.java), serviceConn, Context.BIND_AUTO_CREATE)
    }

    private fun ensureSession() {        val svc = termService ?: return
        if (session != null) return

        val home = File(filesDir, "home").apply { mkdirs() }
        val binDir = File(applicationInfo.nativeLibraryDir)
        val env = arrayOf(
            "HOME=${home.absolutePath}",
            "PATH=/system/bin:/system/xbin:${binDir.absolutePath}",
            "TERM=xterm-256color",
            "TMPDIR=${cacheDir.absolutePath}",
            "LANG=en_US.UTF-8",
            "PWD=${home.absolutePath}",
            "ANDROID_DATA=/data",
            "DSH_PREFIX=${binDir.parentFile?.absolutePath ?: ""}",
        )
        val args = arrayOf("-l")
        // 不同 ROM 的 mksh 位置不同：优先 mksh，回退到必有的 /system/bin/sh
        // （Android 12+ 禁止 untrusted_app 经 app_process 启动 Java 子进程，
        //   所以登录 shell 保持 mksh，cmdlet 通过 `ads` 桥接函数接入，见 SocketRepl）
        val shellPath = if (File("/system/bin/mksh").canExecute()) "/system/bin/mksh"
                        else if (File("/system/xbin/mksh").canExecute()) "/system/xbin/mksh"
                        else "/system/bin/sh"
        // ads() 桥 + dsh() 命令：先写 .profile 再启动会话（mksh 启动即读取，不能有竞态）
        val dshHome = File(filesDir, "dsh-home").apply { mkdirs() }
        val profile = File(home, ".profile")
        run {
            val D: String = "${'$'}"
            val native = binDir.absolutePath
            val binJs = "${filesDir.absolutePath}/dsh-runtime/node_modules/@deepseek-ai/dsh/lib/bin.js"
            profile.writeText(
                """
                |# ADS cmdlet bridge + dsh launcher（由应用生成，每次启动刷新）
                |if [ -f "${D}HOME/ads.env" ]; then
                |  . "${D}HOME/ads.env"
                |  ads() { printf '%s\n%b\n' "${D}ADS_TOKEN" "${D}*" | nc 127.0.0.1 "${D}ADS_PORT" | grep -v '^ads-exit:'; }
                |fi
                |dsh() {
                |  if [ ${D}# -eq 0 ]; then set -- --profile sdk-minimal; fi
                |  LD_LIBRARY_PATH=$native NARB_DISABLE_NATIVE_CACHE=1 DSH_PREFIX=$dshHome DSH_HOME=$dshHome exec $native/libnode.so $binJs "${D}@"
                |}
                |
                """.trimMargin()
            )
        }
        val s = TerminalSession(shellPath, home.absolutePath, args, env, 400, this)
        svc.addSession(s)
        session = s
        terminalView.attachSession(s)
        statusText.text = getString(R.string.fgs_running)
    }

    override fun onResume() { super.onResume(); terminalView.onScreenUpdated() }
    override fun onPause() { super.onPause() }

    override fun onDestroy() {
        unbindService(serviceConn)
        super.onDestroy()
    }

    /** 扩展按键行：移动端 shell 人体工学刚需（ESC/TAB/粘滞CTRL/ALT/方向/常用符号/粘贴） */
    private fun buildExtraKeys(): View {
        fun keyBtn(label: String, sticky: Boolean = false, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                gravity = android.view.Gravity.CENTER
                setTextColor(0xFFCCCCCC.toInt())
                textSize = 14f
                setPadding(0, 16, 0, 16)
                setOnClickListener {
                    onClick()
                    if (sticky) {
                        // 粘滞修饰键视觉反馈
                        val active = if (label == "CTRL") ctrlDown else altDown
                        setBackgroundColor(if (active) 0xFF1D5C4A.toInt() else 0xFF1A2028.toInt())
                    }
                }
            }
        }

        fun writeBytes(vararg data: Int) {
            session?.write(data.map { it.toByte() }.toByteArray(), 0, data.size)
        }

        fun writeText(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            session?.write(b, 0, b.size)
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { setMargins(20, 0, 20, 0) }

        fun add(btn: TextView) { row.addView(btn, params) }

        add(keyBtn("ESC") { writeBytes(0x1b) })
        add(keyBtn("TAB") { writeBytes(0x09) })
        add(keyBtn("CTRL", sticky = true) { ctrlDown = !ctrlDown })
        add(keyBtn("ALT", sticky = true) { altDown = !altDown })
        add(keyBtn("↑") { writeText("\u001b[A") })
        add(keyBtn("↓") { writeText("\u001b[B") })
        add(keyBtn("←") { writeText("\u001b[D") })
        add(keyBtn("→") { writeText("\u001b[C") })
        add(keyBtn("|") { writeText("|") })
        add(keyBtn("/") { writeText("/") })
        add(keyBtn("-") { writeText("-") })
        add(keyBtn("~") { writeText("~") })
        add(keyBtn("📄") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.primaryClip?.getItemAt(0)?.text?.let { writeText(it.toString()) }
        })

        val scroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(0xFF1A2028.toInt())
            addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        return scroll
    }

    // ------------------------------------------------------------------
    // TerminalViewClient
    // ------------------------------------------------------------------
    inner class Client : TerminalViewClient {
        private var fontSp = 30f
        override fun onScale(scale: Float): Float {
            // 捏合缩放字号（8–44sp），返回 1 让视图重置累积因子，下一事件继续增量
            fontSp = (fontSp * scale).coerceIn(8f, 44f)
            terminalView.setTextSize(fontSp.toInt())
            return 1f
        }
        override fun onSingleTapUp(e: MotionEvent?) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(terminalView, 0)
        }
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
        override fun onLongPress(event: MotionEvent?) = false
        override fun readControlKey() = ctrlDown
        override fun readAltKey() = altDown
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) = false
        override fun onEmulatorSet() { terminalView.onScreenUpdated() }
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    // ------------------------------------------------------------------
    // TerminalSessionClient
    // ------------------------------------------------------------------
    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}

    override fun onSessionFinished(finishedSession: TerminalSession) {
        runOnUiThread {
            statusText.text = "会话已退出 (exit=${finishedSession.exitStatus})"
        }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
    override fun onPasteTextFromClipboard(session: TerminalSession?) {}

    override fun onBell(session: TerminalSession) {}

    override fun onColorsChanged(session: TerminalSession) { terminalView.onScreenUpdated() }

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}

    override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}
