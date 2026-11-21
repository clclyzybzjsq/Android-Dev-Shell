package dev.ads.shell.cmdlets

import dev.ads.shell.core.Cmdlet
import dev.ads.shell.core.CmdletRegistry
import dev.ads.shell.core.Ctx
import dev.ads.shell.core.ExitShellException
import dev.ads.shell.core.JValue

class CoreCmdlets {
    companion object {
        fun registerAll(r: CmdletRegistry) {
            r.register(Echo()); r.register(Cd()); r.register(Pwd()); r.register(Env())
            r.register(SetEnv()); r.register(Unset()); r.register(Exit()); r.register(Help())
            r.register(Which()); r.register(Sh())
            r.alias("dir", "ls")

            // PowerShell 兼容别名层：让 pwsh 语料训练出的 agent 零成本上手。
            // 覆盖最高频的 Verb-Noun 命令；未覆盖的复杂 cmdlet 见 help。
            r.alias("Where-Object", "where")
            r.alias("Select-Object", "select")
            r.alias("Sort-Object", "sort")
            r.alias("Measure-Object", "count")
            r.alias("Get-ChildItem", "ls")
            r.alias("Get-Content", "cat")
            r.alias("Get-Item", "stat")
            r.alias("Get-Location", "pwd")
            r.alias("Set-Location", "cd")
            r.alias("Write-Output", "echo")
            r.alias("Write-Host", "echo")
            r.alias("Get-Help", "help")
            r.alias("Remove-Item", "rm")
            r.alias("Copy-Item", "cp")
            r.alias("Move-Item", "mv")
            r.alias("New-Item", "touch")
            r.alias("Get-Variable", "env")
            r.alias("ConvertTo-Json", "tojson")
            r.alias("ConvertFrom-Json", "fromjson")
            r.alias("Invoke-WebRequest", "http")
        }

        class Echo : Cmdlet("echo", "[-n] <文本...>") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                val noNl = args.firstOrNull() == "-n"
                val rest = if (noNl) args.drop(1) else args
                if (rest.isEmpty() && input.any()) {
                    for (v in input) yield(v)
                } else {
                    yield(JValue.JStr(rest.joinToString(" ")))
                }
                if (!noNl) { /* 换行由渲染层处理？此处保真：对象输出不自动加换行 */ }
            }
        }

        class Cd : Cmdlet("cd", "<目录>") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                val target = args.firstOrNull()
                    ?: ctx.env["HOME"] ?: ctx.cwd.absolutePath
                val f = ctx.resolve(target)
                if (!f.isDirectory) ctx.error("cd: 不是目录: $target")
                else { ctx.cwd = f.canonicalFile; ctx.env["PWD"] = ctx.cwd.absolutePath }
            }
        }

        class Pwd : Cmdlet("pwd", "") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) =
                sequenceOf(JValue.JStr(ctx.cwd.absolutePath))
        }

        class Env : Cmdlet("env", "[-k key]  过滤环境变量") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                val filter = if (args.firstOrNull() == "-k") args.getOrNull(1) else null
                for ((k, v) in ctx.env) {
                    if (filter == null || k.contains(filter))
                        yield(JValue.JObj(linkedMapOf("key" to JValue.JStr(k), "value" to JValue.JStr(v))))
                }
            }
        }

        class SetEnv : Cmdlet("setenv", "<KEY> <value>") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                if (args.size < 2) ctx.error(usage())
                else { ctx.env[args[0]] = args[1]; ctx.env[args[0]] = args[1] }
            }
        }

        class Unset : Cmdlet("unset", "<KEY>") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                args.forEach { ctx.env.remove(it) }
            }
        }

        class Exit : Cmdlet("exit", "[code]") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx): Sequence<JValue> {
                throw ExitShellException(args.firstOrNull()?.toIntOrNull() ?: 0)
            }
        }

        class Help : Cmdlet("help", "[命令名]") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                val target = args.firstOrNull()
                if (target != null) {
                    val cmd = ShellRegistry.ref.get(target)
                    if (cmd != null) yield(JValue.JStr("${cmd.name} — ${cmd.usage()}"))
                    else yield(JValue.JStr("$target: 内置命令未找到（可能是外部命令，直接运行即可）"))
                } else {
                    val names = ShellRegistry.ref.all().joinToString("  ") { it.name }
                    yield(JValue.JStr("内置命令:\n$names\n\n管道示例:\n  ls | where size > 1000 | sort size | select name size\n  apps | where label =~ *shiz* | count\n  http -j https://httpbin.org/json | select body"))
                }
            }
        }

        class Which : Cmdlet("which", "<命令>") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                for (name in args) {
                    val isB = ShellRegistry.ref.isBuiltin(name)
                    val path = findOnPath(name, ctx)
                    when {
                        isB -> yield(JValue.JObj(linkedMapOf("name" to JValue.JStr(name), "type" to JValue.JStr("builtin"))))
                        path != null -> yield(JValue.JObj(linkedMapOf("name" to JValue.JStr(name), "type" to JValue.JStr("external"), "path" to JValue.JStr(path))))
                        else -> yield(JValue.JObj(linkedMapOf("name" to JValue.JStr(name), "type" to JValue.JStr("not-found"))))
                    }
                }
            }

            fun findOnPath(name: String, ctx: Ctx): String? {
                val path = ctx.env["PATH"] ?: "/system/bin"
                for (d in path.split(':')) {
                    val f = java.io.File(d, name)
                    if (f.canExecute()) return f.absolutePath
                }
                return null
            }
        }

        class Sh : Cmdlet("sh", "<命令行...>  交给 /system/bin/sh 执行（支持 | > 等 POSIX 语法）") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx): Sequence<JValue> {
                if (args.isEmpty()) ctx.error("sh: 缺少命令")
                return ctx.external.pipe(listOf("/system/bin/sh", "-c", args.joinToString(" ")), input, ctx)
            }
        }
    }
}

/** 全局注册表引用（help/which 需要访问） */
object ShellRegistry {
    lateinit var ref: dev.ads.shell.core.CmdletRegistry
}
