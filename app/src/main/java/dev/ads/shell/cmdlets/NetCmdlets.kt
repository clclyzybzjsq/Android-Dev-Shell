package dev.ads.shell.cmdlets

import dev.ads.shell.core.Cmdlet
import dev.ads.shell.core.CmdletRegistry
import dev.ads.shell.core.Ctx
import dev.ads.shell.core.JValue
import java.net.HttpURLConnection
import java.net.URL

class NetCmdlets {
    companion object {
        fun registerAll(r: CmdletRegistry) {
            r.register(Http()); r.register(Dns())
        }

        class Http : Cmdlet("http", "[-X method] [-d body] [-H h] [-j] <url>  -j 解析 body 为 JSON") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                var method = "GET"
                var body: String? = null
                var parse = false
                val headers = ArrayList<Pair<String, String>>()
                var url: String? = null

                var i = 0
                while (i < args.size) {
                    when (args[i]) {
                        "-X" -> { method = args.getOrNull(i + 1) ?: "GET"; i += 2 }
                        "-d", "--data" -> { body = args.getOrNull(i + 1); i += 2 }
                        "-H" -> {
                            val h = args.getOrNull(i + 1) ?: ""
                            val c = h.indexOf(':')
                            if (c > 0) headers.add(h.substring(0, c).trim() to h.substring(c + 1).trim())
                            i += 2
                        }
                        "-j", "--json" -> { parse = true; i++ }
                        else -> { url = args[i]; i++ }
                    }
                }
                if (url == null) { ctx.error(usage()); return@sequence }
                if (body != null && method == "GET") method = "POST"

                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.requestMethod = method
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 30_000
                    headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                    if (body != null) {
                        conn.doOutput = true
                        conn.getOutputStream().use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                    val code = conn.responseCode
                    val respHeaders = conn.headerFields.filterKeys { it != null }
                        .mapKeys { it.key ?: "" }
                        .mapValues { (_, v) -> JValue.JArr(v.map { JValue.JStr(it) }) }
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

                    val fields = LinkedHashMap<String, JValue>(
                        linkedMapOf(
                            "status" to JValue.JLong(code.toLong()),
                            "ok" to JValue.JBool(code in 200..299),
                            "headers" to JValue.JObj(LinkedHashMap(respHeaders)),
                        )
                    )
                    fields["body"] = if (parse) {
                        try { Json2.parse(text) } catch (e: Exception) { JValue.JStr(text) }
                    } else JValue.JStr(text)
                    yield(JValue.JObj(fields))
                    ctx.lastExit = if (code in 200..299) 0 else 1
                } catch (e: Exception) {
                    ctx.error("http: ${e.message ?: e.javaClass.simpleName}")
                    ctx.lastExit = 1
                }
            }
        }

        class Dns : Cmdlet("dns", "<主机名>  解析为地址列表") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                for (h in args) {
                    try {
                        java.net.InetAddress.getAllByName(h).forEach {
                            yield(JValue.JObj(linkedMapOf(
                                "host" to JValue.JStr(h),
                                "addr" to JValue.JStr(it.hostAddress ?: ""),
                                "loopback" to JValue.JBool(it.isLoopbackAddress),
                            )))
                        }
                    } catch (e: Exception) { ctx.error("dns: $h: ${e.message}") }
                }
            }
        }
    }
}

// 避免与 core.Json 命名冲突的别名
private object Json2 {
    fun parse(t: String) = dev.ads.shell.core.Json.parse(t)
}
