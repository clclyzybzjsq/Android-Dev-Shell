package dev.ads.shell

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.ads.shell.core.Ctx
import dev.ads.shell.core.Interpreter
import dev.ads.shell.core.JValue
import dev.ads.shell.core.ProcessExternalRunner
import java.io.File

/**
 * 对象管道控制台（ADS REPL）：
 *  - 输入一行管道，内置命令以 JSON 对象流转，外部命令经 ProcessBuilder 执行；
 *  - 面向 coding agent 的结构化交互入口（后续加 unix socket 接口）。
 */
class PipeConsoleActivity : AppCompatActivity() {

    private lateinit var interpreter: Interpreter
    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView
    private lateinit var ctx: Ctx
    private val history = ArrayList<String>()
    private var historyPos = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        interpreter = Interpreter(Interpreter.defaultRegistry())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101418.toInt())
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = "⌗ ADS 对象管道控制台  (输入 help 查看示例)"
            setTextColor(0xFF4EC9B0.toInt())
            textSize = 14f
        }
        root.addView(title)

        output = TextView(this).apply {
            setTextColor(0xFFD4D4D4.toInt())
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scroll = ScrollView(this).apply { addView(output) }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        input = EditText(this).apply {
            hint = "例如: ls | where size > 1000 | sort -d size | select name size"
            setTextColor(0xFFE6E6E6.toInt())
            setHintTextColor(0xFF606060.toInt())
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
        }
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) { runLine(); true } else false
        }
        input.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && historyPos > 0) {
                    historyPos--; input.setText(history[historyPos]); true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (historyPos < history.size - 1) { historyPos++; input.setText(history[historyPos]) }
                    else { historyPos = history.size; input.setText("") }
                    true
                } else false
            } else false
        }
        root.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
        print("ADS 0.1.0 — JSON 对象管道 shell（无 root / 原生 / 非 VM）\n")

        val home = File(filesDir, "home").apply { mkdirs() }
        ctx = Ctx(
            env = mutableMapOf(
                "PATH" to "/system/bin:/system/xbin:${applicationInfo.nativeLibraryDir}",
                "HOME" to home.absolutePath,
                "TERM" to "xterm-256color",
            ),
            cwd = home,
            out = { s -> appendOut(s) },
            err = { s -> appendOut(s) },
            bridge = AndroidBridgeImpl(applicationContext),
            external = ProcessExternalRunner(),
            host = applicationContext,
        )

        // agent/adb 驱动入口：am start --es autorun 'cmd1; cmd2; ...'
        runAutorun(intent)
    }

    /** adb 重复驱动时（app 存活）也会经 onNewIntent 重新投递 autorun */
    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        runAutorun(intent)
    }

    private fun runAutorun(intent: android.content.Intent?) {
        val script = intent?.getStringExtra("autorun") ?: return
        if (script.isBlank()) return
        print("\n[autorun]\n")
        window.decorView.post {
            script.split('\n', ';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                execLine(line)
            }
            print("\n[autorun done, exit=${ctx.lastExit}]\n")
        }
    }

    private fun execLine(line: String) {
        appendOut("❯ $line\n")
        interpreter.runLine(line, ctx)
    }

    private fun runLine() {
        val line = input.text.toString()
        if (line.isBlank()) return
        input.setText("")
        history.add(line); historyPos = history.size
        appendOut("❯ $line\n")
        interpreter.runLine(line, ctx)
        appendOut("\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun appendOut(s: String) {
        runOnUiThread {
            output.append(s)
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun print(s: String) = appendOut(s)
}
