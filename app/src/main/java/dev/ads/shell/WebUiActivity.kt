package dev.ads.shell

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.ads.shell.dsh.DshRuntime
import java.io.File

/** 🌐 官方 harness WebUI：DshHost 以 web profile 启动 dsh，WebView 加载其本地服务 */
class WebUiActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var loadedUrl: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
        }
        web.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = false
        }
        web.addJavascriptInterface(Bridge(), "DshBridge")

        DshHost.setSinks(
            onLine = { line ->
                val escaped = org.json.JSONObject.quote(line)
                runOnUiThread { web.evaluateJavascript("window.__dshLine && window.__dshLine($escaped)", null) }
            },
            onExit = { code ->
                runOnUiThread {
                    web.evaluateJavascript(
                        "window.__dshLine && window.__dshLine(${org.json.JSONObject.quote("[dsh 进程退出 code=$code]")})",
                        null
                    )
                }
            }
        )
        DshHost.setUrlSink { url ->
            runOnUiThread {
                if (loadedUrl == null) {
                    loadedUrl = url
                    web.loadUrl(url)
                }
            }
        }

        if (DshHost.isRunning()) {
            // 进程已在跑：恢复加载（URL 若未知则等待 stdout 重新上报——web 模式重启进程更稳）
            if (loadedUrl != null) web.loadUrl(loadedUrl!!)
            else {
                DshHost.stop()
                Thread.sleep(300)
                ensureRuntimeThenStartWeb()
                web.loadUrl("about:blank")
            }
        } else {
            ensureRuntimeThenStartWeb()
            web.loadUrl("about:blank")
        }

        // 悬浮终端入口：ADS shell 工具
        val termBtn = TextView(this).apply {
            text = "⌨"
            textSize = 22f
            setTextColor(0xFF4EC9B0.toInt())
            setPadding(20, 14, 20, 14)
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.BOTTOM or Gravity.END
        lp.setMargins(0, 0, 32, 120)
        root.addView(termBtn, lp)
        termBtn.setOnClickListener {
            startActivity(android.content.Intent(this, MainActivity::class.java))
        }
    }

    /** 后台安装闭包（幂等）→ 启动官方 web profile */
    private fun ensureRuntimeThenStartWeb() {
        Thread {
            try {
                DshRuntime.installIfNeeded(this) { }
            } catch (_: Exception) {}
            runOnUiThread { DshHost.start(this, "web") }
        }.apply { isDaemon = true; name = "dsh-runtime-install" }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        // dsh web 服务保留运行（宿主重建时恢复）
    }

    inner class Bridge {
        @JavascriptInterface
        fun send(line: String) { DshHost.send(line) }

        @JavascriptInterface
        fun isRunning(): Boolean = DshHost.isRunning()
    }
}
