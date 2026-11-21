package dev.ads.shell.core

import java.io.File

/**
 * 管道解释器。
 *
 * 执行模型：
 *  - 纯内置管道：JValue 序列在各 cmdlet 间惰性流转，终端输出时对象以 JSON 呈现。
 *  - 含外部命令的管道：内置 -> 外部 时把 JValue 渲染为文本行喂给 stdin；
 *    外部 -> 内置 时把 stdout 按行拆为 JStr 流入下游。
 *  - 单条外部命令（无管道、无重定向）：交给 ExternalRunner.interactive()（终端场景挂 PTY），
 *    拒绝则回退 pipe 执行。
 */
class Interpreter(private val registry: CmdletRegistry) {

    fun runLine(line: String, ctx: Ctx) {
        val program = try {
            val tokens = Lexer.tokenize(line, ctx.env)
            if (tokens.isEmpty()) return
            Parser.parse(tokens, ctx.cwd)
        } catch (e: Exception) {
            ctx.error(e.message ?: "解析失败")
            ctx.lastExit = 2
            return
        }
        for (pipeline in program.pipelines) {
            if (ctx.exitRequested) return
            runPipeline(pipeline, ctx)
        }
    }

    fun runPipeline(p: Parser.Pipeline, ctx: Ctx) {
        val stages = p.stages
        if (stages.isEmpty()) return

        val lastStage = stages.last()
        var seq: Sequence<JValue> = emptySequence()

        try {
            var prevExternal: List<String>? = null

            for ((idx, stage) in stages.withIndex()) {
                val argv = listOf(stage.name) + stage.args
                val cmdlet = registry.get(stage.name)
                val isLast = idx == stages.size - 1

                if (cmdlet != null) {
                    // 内置命令
                    val input = seq
                    seq = cmdlet.run(stage.args, input, ctx)
                    prevExternal = null
                } else {
                    // 外部命令
                    if (stages.size == 1 && p.redirect == null && ctx.external.interactive(argv, ctx)) {
                        ctx.lastExit = 0
                        return
                    }
                    val input = if (idx == 0) emptySequence() else seq
                    if (!isLast) {
                        // 非末端外部命令：立刻物化收集（外部命令总是急切执行）
                        val collected = ctx.external.pipe(argv, input, ctx).toList()
                        seq = collected.asSequence()
                    } else {
                        prevExternal = argv
                        seq = input
                    }
                }
            }

            // 处理末端外部命令
            if (prevExternal != null) {
                if (p.redirect != null) {
                    // 外部命令 + 重定向：收集输出写入文件
                    val sb = StringBuilder()
                    for (v in ctx.external.pipe(prevExternal, seq, ctx)) { sb.append(v.toLine()).append('\n') }
                    try {
                        val f = ctx.resolve(p.redirect.path)
                        if (p.redirect.append) f.appendText(sb.toString()) else f.writeText(sb.toString())
                    } catch (e: Exception) {
                        ctx.error("写入 ${p.redirect.path} 失败: ${e.message}")
                        ctx.lastExit = 1
                    }
                } else {
                    for (v in ctx.external.pipe(prevExternal, seq, ctx)) {
                        ctx.out(v.toLine()); ctx.out("\n")
                    }
                }
                seq = emptySequence()
            } else {
                renderFinal(seq, p.redirect, ctx)
            }
        } catch (e: ExitShellException) {
            throw e
        } catch (e: Exception) {
            ctx.error("${lastStage.name}: ${e.message ?: e.javaClass.simpleName}")
            ctx.lastExit = 1
        }
    }

    private fun renderFinal(seq: Sequence<JValue>, redirect: Parser.Redirect?, ctx: Ctx) {
        if (redirect == null) {
            for (v in seq) { ctx.out(v.toDisplay()); ctx.out("\n") }
            return
        }
        val f = ctx.resolve(redirect.path)
        try {
            val sb = StringBuilder()
            for (v in seq) { sb.append(v.toDisplay()); sb.append('\n') }
            if (redirect.append) f.appendText(sb.toString()) else f.writeText(sb.toString())
        } catch (e: Exception) {
            ctx.error("写入 ${redirect.path} 失败: ${e.message}")
            ctx.lastExit = 1
        }
    }

    companion object {
        /** 默认内置注册 */
        fun defaultRegistry(): CmdletRegistry {
            val r = CmdletRegistry()
            dev.ads.shell.cmdlets.CoreCmdlets.registerAll(r)
            dev.ads.shell.cmdlets.FileCmdlets.registerAll(r)
            dev.ads.shell.cmdlets.PipeCmdlets.registerAll(r)
            dev.ads.shell.cmdlets.NetCmdlets.registerAll(r)
            dev.ads.shell.cmdlets.AndroidCmdlets.registerAll(r)
            dev.ads.shell.cmdlets.ShizukuCmdlets.registerAll(r)
            dev.ads.shell.cmdlets.ShellRegistry.ref = r
            return r
        }
    }
}
