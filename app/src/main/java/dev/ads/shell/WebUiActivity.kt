package dev.ads.shell

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** 🌐 官方 harness WebUI：DshHost 以 web profile 启动 dsh，WebView 加载其本地服务 */
class WebUiActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var loadedUrl: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
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
                DshHost.start(this, "web")
                web.loadUrl("about:blank")
            }
        } else {
            DshHost.start(this, "web")
            web.loadUrl("about:blank")
        }
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
