package dev.ads.shell.core

import dev.ads.shell.core.Lexer.Kind
import dev.ads.shell.core.Lexer.Token

/** 语法解析：token 流 -> Program（多条 Pipeline） */
object Parser {

    data class Command(val name: String, val args: List<String>, val rawName: String)

    data class Pipeline(val stages: List<Command>, val redirect: Redirect?)

    data class Redirect(val append: Boolean, val path: String)

    data class Program(val pipelines: List<Pipeline>)

    class ParseException(msg: String) : Exception(msg)

    fun parse(tokensIn: List<Token>, cwd: java.io.File): Program {
        val tokens = Lexer.globExpand(tokensIn, cwd)
        val pipelines = ArrayList<Pipeline>()
        var i = 0
        val n = tokens.size

        fun isBoundary(t: Token) = t.kind == Kind.SEMI

        while (i < n) {
            if (isBoundary(tokens[i])) { i++; continue }
            val stages = ArrayList<Command>()
            var redirect: Redirect? = null
            while (i < n && !isBoundary(tokens[i])) {
                val t = tokens[i]
                if (t.kind == Kind.PIPE) {
                    if (stages.isEmpty()) throw ParseException("管道符 | 前缺少命令")
                    i++
                    continue
                }
                if (t.kind == Kind.REDIR_OUT || t.kind == Kind.REDIR_APPEND) {
                    if (i + 1 >= n || tokens[i + 1].kind != Kind.WORD)
                        throw ParseException("重定向 ${t.text} 后缺少文件名")
                    redirect = Redirect(t.kind == Kind.REDIR_APPEND, tokens[i + 1].text)
                    i += 2
                    continue
                }
                // WORD: 命令或参数
                if (t.kind != Kind.WORD) throw ParseException("意外 token: ${t.raw}")
                val name = t.text
                i++
                val args = ArrayList<String>()
                while (i < n && tokens[i].kind == Kind.WORD) { args.add(tokens[i].text); i++ }
                stages.add(Command(name, args, t.raw))
            }
            if (stages.isNotEmpty()) pipelines.add(Pipeline(stages, redirect))
        }
        return Program(pipelines)
    }
}
