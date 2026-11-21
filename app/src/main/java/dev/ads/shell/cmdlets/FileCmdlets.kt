package dev.ads.shell.cmdlets

import dev.ads.shell.core.Cmdlet
import dev.ads.shell.core.CmdletRegistry
import dev.ads.shell.core.Ctx
import dev.ads.shell.core.JValue
import java.io.File

class FileCmdlets {
    companion object {
        fun registerAll(r: CmdletRegistry) {
            r.register(Ls()); r.register(Cat()); r.register(Head()); r.register(Tail())
            r.register(Mkdir()); r.register(Rm()); r.register(Cp()); r.register(Mv())
            r.register(Touch()); r.register(Find()); r.register(Stat()); r.register(Wc())
            r.alias("ll", "ls")
        }

        fun fileToObj(f: File, base: File?): JValue.JObj {
            val fields = linkedMapOf<String, JValue>(
                "name" to JValue.JStr(f.name.ifEmpty { f.absolutePath }),
                "dir" to JValue.JBool(f.isDirectory),
            )
            if (base != null) fields["path"] = JValue.JStr(f.relativeToOrNull(base)?.invariantSeparatorsPath ?: f.absolutePath)
            else fields["path"] = JValue.JStr(f.absolutePath)
            fields["size"] = JValue.JLong(if (f.isDirectory) 0L else f.length())
            fields["mtime"] = JValue.JLong(f.lastModified())
            return JValue.JObj(fields)
        }

        class Ls : Cmdlet("ls", "[-a] [-R] [路径...]  输出对象：name/path/dir/size/mtime") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                val all = "-a" in args || "-A" in args
                val recursive = "-R" in args
                val paths = args.filter { !it.startsWith("-") }.ifEmpty { listOf(".") }

                suspend fun SequenceScope<JValue>.emitDir(d: File, base: File, depth: Int) {
                    val children = d.listFiles()?.filter { all || !it.name.startsWith(".") }?.sortedBy { it.name }
                    children?.forEach { c ->
                        yield(fileToObj(c, base))
                        if (recursive && c.isDirectory) emitDir(c, base, depth + 1)
                    }
                }

                for (p in paths) {
                    val f = ctx.resolve(p)
                    when {
                        !f.exists() -> ctx.error("ls: 不存在: $p")
                        f.isFile -> yield(fileToObj(f, null))
                        else -> {
                            if (paths.size > 1) yield(JValue.JObj(linkedMapOf("name" to JValue.JStr(p + ":"), "dir" to JValue.JBool(true), "path" to JValue.JStr(f.absolutePath), "size" to JValue.JLong(0), "mtime" to JValue.JLong(0))))
                            emitDir(f, f, 0)
                        }
                    }
                }
            }
        }

        class Cat : Cmdlet("cat", "<文件...>  也可消费管道输入") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                if (args.isEmpty()) {
                    for (v in input) yield(v)
                } else for (p in args) {
                    val f = ctx.resolve(p)
                    if (!f.isFile) { ctx.error("cat: 不是文件: $p"); continue }
                    // 整文件按行输出，保持字符串语义
                    f.useLines { lines -> for (l in lines) yield(JValue.JStr(l)) }
                }
            }
        }

        class Head : Cmdlet("head", "[-n 行数] [文件]") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                val n = args.indexOf("-n").let { if (it >= 0) args.getOrNull(it + 1)?.toIntOrNull() ?: 10 else 10 }
                val file = args.firstOrNull { !it.startsWith("-") && it.toIntOrNull() == null && it != (args.getOrNull(args.indexOf("-n") + 1)) }
                val src: Sequence<JValue> = if (file != null) {
                    ctx.resolve(file).useLines { l -> l.take(n).map { JValue.JStr(it) as JValue }.toList() }.asSequence()
                } else input
                var c = 0
                for (v in src) { if (c++ >= n) break; yield(v) }
            }
        }

        class Tail : Cmdlet("tail", "[-n 行数] [文件]") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                val n = args.indexOf("-n").let { if (it >= 0) args.getOrNull(it + 1)?.toIntOrNull() ?: 10 else 10 }
                val src: List<JValue> = if (args.any { !it.startsWith("-") && it != args.getOrNull(args.indexOf("-n") + 1) }) {
                    val file = args.first { !it.startsWith("-") && it != args.getOrNull(args.indexOf("-n") + 1) }
                    ctx.resolve(file).useLines { l -> l.toList().map { JValue.JStr(it) as JValue } }
                } else input.toList()
                src.takeLast(n).forEach { yield(it) }
            }
        }

        class Mkdir : Cmdlet("mkdir", "[-p] <目录...>") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                val parents = "-p" in args
                for (p in args.filter { !it.startsWith("-") }) {
                    val f = ctx.resolve(p)
                    val ok = if (parents) f.mkdirs() else f.mkdir()
                    if (!ok && !f.isDirectory) ctx.error("mkdir: 失败: $p")
                }
            }
        }

        class Rm : Cmdlet("rm", "[-r] [-f] <路径...>  ⚠ 删除文件/目录") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                val recursive = "-r" in args || "-rf" in args
                val force = "-f" in args || "-rf" in args
                for (p in args.filter { !it.startsWith("-") }) {
                    val f = ctx.resolve(p)
                    if (!f.exists()) { if (!force) ctx.error("rm: 不存在: $p"); continue }
                    val ok = deleteRec(f, recursive)
                    if (!ok && !force) ctx.error("rm: 删除失败(目录需 -r): $p")
                }
            }

            fun deleteRec(f: File, recursive: Boolean): Boolean {
                if (f.isDirectory && recursive) f.listFiles()?.forEach { deleteRec(it, true) }
                return f.delete()
            }
        }

        class Cp : Cmdlet("cp", "<源> <目标>") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                if (args.size < 2) { ctx.error(usage()); return@sequence }
                val src = ctx.resolve(args[0]); val dst = ctx.resolve(args[1])
                if (!src.exists()) { ctx.error("cp: 不存在: $src"); return@sequence }
                val target = if (dst.isDirectory) File(dst, src.name) else dst
                src.copyTo(target, overwrite = true)
            }
        }

        class Mv : Cmdlet("mv", "<源> <目标>") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                if (args.size < 2) { ctx.error(usage()); return@sequence }
                val src = ctx.resolve(args[0]); val dst = ctx.resolve(args[1])
                val target = if (dst.isDirectory) File(dst, src.name) else dst
                if (!src.renameTo(target)) {
                    src.copyTo(target, overwrite = true)
                    src.delete()
                }
            }
        }

        class Touch : Cmdlet("touch", "<文件...>") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                for (p in args) {
                    val f = ctx.resolve(p)
                    if (!f.exists()) f.createNewFile() else f.setLastModified(System.currentTimeMillis())
                }
            }
        }

        class Find : Cmdlet("find", "[-name glob] [起始目录]  输出对象流") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                val nameIdx = args.indexOf("-name")
                val glob = if (nameIdx >= 0) args.getOrNull(nameIdx + 1) else null
                val start = args.firstOrNull { !it.startsWith("-") && it != args.getOrNull(nameIdx + 1) } ?: "."
                val re = glob?.let { dev.ads.shell.core.Lexer.globToRegex(it.substringAfterLast('/')) }

                suspend fun SequenceScope<JValue>.walk(d: File) {
                    d.listFiles()?.forEach { f ->
                        if (re == null || re.matches(f.name)) yield(fileToObj(f, null))
                        if (f.isDirectory) walk(f)
                    }
                }
                val sf = ctx.resolve(start)
                if (!sf.isDirectory) ctx.error("find: 不是目录: $start") else walk(sf)
            }
        }

        class Stat : Cmdlet("stat", "<路径>") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                for (p in args) {
                    val f = ctx.resolve(p)
                    if (!f.exists()) { ctx.error("stat: 不存在: $p"); continue }
                    yield(JValue.JObj(linkedMapOf(
                        "path" to JValue.JStr(f.absolutePath),
                        "dir" to JValue.JBool(f.isDirectory),
                        "size" to JValue.JLong(f.length()),
                        "mtime" to JValue.JLong(f.lastModified()),
                        "canWrite" to JValue.JBool(f.canWrite()),
                        "canRead" to JValue.JBool(f.canRead()),
                        "canExec" to JValue.JBool(f.canExecute()),
                    )))
                }
            }
        }

        class Wc : Cmdlet("wc", "统计管道输入行数（同 count）") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) =
                sequenceOf(JValue.JLong(input.count().toLong()))
        }
    }
}
