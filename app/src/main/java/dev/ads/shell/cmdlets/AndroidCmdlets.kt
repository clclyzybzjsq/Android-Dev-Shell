package dev.ads.shell.cmdlets

import dev.ads.shell.core.Cmdlet
import dev.ads.shell.core.CmdletRegistry
import dev.ads.shell.core.Ctx
import dev.ads.shell.core.JValue

class AndroidCmdlets {
    companion object {
        fun registerAll(r: CmdletRegistry) {
            r.register(Apps()); r.register(Battery()); r.register(Device()); r.register(Clip())
            r.register(Notify()); r.register(Open())
        }

        class Apps : Cmdlet("apps", "[关键词]  列出已安装应用（label/package）") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                val kw = args.firstOrNull()?.lowercase()
                for ((label, pkg) in ctx.bridge.listApps()) {
                    if (kw == null || label.lowercase().contains(kw) || pkg.lowercase().contains(kw))
                        yield(JValue.JObj(linkedMapOf("label" to JValue.JStr(label), "package" to JValue.JStr(pkg))))
                }
            }
        }

        class Battery : Cmdlet("battery", "电池状态对象") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) =
                sequenceOf(JValue.JObj(ctx.bridge.battery()))
        }

        class Device : Cmdlet("device", "设备信息对象") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) =
                sequenceOf(JValue.JObj(ctx.bridge.deviceInfo()))
        }

        class Clip : Cmdlet("clip", "get|set <文本>") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                when (args.firstOrNull()) {
                    "get" -> yield(JValue.JStr(ctx.bridge.clipboardGet() ?: ""))
                    "set" -> {
                        val text = if (args.size > 1) args.drop(1).joinToString(" ")
                        else input.joinToString("\n") { it.toLine() }
                        ctx.bridge.clipboardSet(text)
                        yield(JValue.JStr("已复制 ${text.length} 字符"))
                    }
                    else -> ctx.error(usage())
                }
            }
        }

        class Notify : Cmdlet("notify", "<标题> [正文]") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                val title = args.firstOrNull() ?: "ads"
                val body = args.drop(1).joinToString(" ").ifEmpty { input.joinToString("\n") { it.toLine() } }
                val ok = ctx.bridge.notifyUser(title, body)
                yield(JValue.JBool(ok))
            }
        }

        class Open : Cmdlet("open", "<url|包名>  打开链接或应用") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                val uri = args.firstOrNull() ?: input.firstOrNull()?.toLine()
                if (uri == null) ctx.error(usage())
                else yield(JValue.JBool(ctx.bridge.openUri(uri)))
            }
        }
    }
}

class ShizukuCmdlets {
    companion object {
        fun registerAll(r: CmdletRegistry) {
            r.register(Shz()); r.register(ShExec())
        }

        class Shz : Cmdlet("shz", "查看 Shizuku 连接状态") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                val connected = ctx.bridge.shizukuConnected()
                yield(JValue.JObj(linkedMapOf(
                    "connected" to JValue.JBool(connected),
                    "uid" to JValue.JStr(if (connected) "2000 (adb shell)" else "-"),
                    "hint" to JValue.JStr(if (connected) "可用 shexec <命令> 执行 pm/am/settings/dumpsys"
                        else "未连接：启动 Shizuku（无线调试或 root），并授权本应用"),
                )))
            }
        }

        class ShExec : Cmdlet("shexec", "<命令...>  经 Shizuku (uid 2000) 执行，如 shexec pm list packages") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                if (args.isEmpty()) { ctx.error(usage()); return@sequence }
                val stdin = input.joinToString("\n") { it.toLine() }
                val command = buildString {
                    append(args.joinToString(" "))
                    if (stdin.isNotEmpty()) { append('\n'); append(stdin) }
                }
                yield(JValue.JStr(ctx.bridge.shizukuExec(command)))
            }
        }
    }
}
