package dev.ads.shell.core

/**
 * 词法分析：把一行命令切成 token。
 * 支持：单/双引号、反斜杠转义、管道 | 、分隔 ; 与换行、重定向 > >>、变量替换 $VAR/${VAR}、
 * 通配符 * ? 的词法标记（展开在解析后进行，避免引号内的 * 被误展开）。
 */
object Lexer {

    enum class Kind { WORD, PIPE, SEMI, REDIR_OUT, REDIR_APPEND }

    data class Token(val kind: Kind, val text: String, val raw: String)

    class LexException(msg: String) : Exception(msg)

    fun tokenize(line: String, env: Map<String, String>): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        val n = line.length

        fun readVar(start: Int): String {
            // start 指向 '$'
            var j = start + 1
            if (j < n && line[j] == '{') {
                val close = line.indexOf('}', j)
                if (close == -1) throw LexException("变量 \${ 缺少右花括号")
                return line.substring(j + 1, close).also { i = close + 1 }
            }
            while (j < n && (line[j].isLetterOrDigit() || line[j] == '_')) j++
            if (j == start + 1) throw LexException("孤立的 '$' @${start}")
            return line.substring(start + 1, j).also { i = j }
        }

        while (i < n) {
            val c = line[i]
            when {
                c.isWhitespace() -> i++
                c == '#' -> i = n // 行注释
                c == '|' -> { tokens.add(Token(Kind.PIPE, "|", "|")); i++ }
                c == ';' -> { tokens.add(Token(Kind.SEMI, ";", ";")); i++ }
                c == '>' -> {
                    if (i + 1 < n && line[i + 1] == '>') {
                        tokens.add(Token(Kind.REDIR_APPEND, ">>", ">>")); i += 2
                    } else if (i + 1 < n && line[i + 1] == '=') {
                        // >= 属于 where 比较运算符，不是重定向
                        tokens.add(Token(Kind.WORD, ">=", "'>='")); i += 2
                    } else { tokens.add(Token(Kind.REDIR_OUT, ">", ">")); i++ }
                }
                c == '<' && i + 1 < n && line[i + 1] == '=' -> {
                    tokens.add(Token(Kind.WORD, "<=", "'<='")); i += 2
                }
                c == '$' -> {
                    val name = readVar(i)
                    val v = env[name] ?: System.getenv(name) ?: ""
                    tokens.add(Token(Kind.WORD, v, "\$$name"))
                }
                c == '\'' || c == '"' -> {
                    val quote = c
                    val rawStart = i
                    i++
                    val sb = StringBuilder()
                    while (true) {
                        if (i >= n) throw LexException("引号未闭合 @${rawStart}")
                        when (val ch = line[i]) {
                            quote -> { i++; break }
                            '\\' -> {
                                if (quote == '\'') { sb.append('\\'); i++ }
                                else {
                                    i++
                                    if (i >= n) throw LexException("转义序列未闭合")
                                    when (val e = line[i]) {
                                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                                        '$' -> sb.append('$'); '`' -> sb.append('`')
                                        '"' -> sb.append('"'); '\'' -> sb.append('\''); '\\' -> sb.append('\\')
                                        else -> { sb.append('\\'); sb.append(e) }
                                    }
                                    i++
                                }
                            }
                            '$' -> {
                                if (quote == '\'') { sb.append('$'); i++ }
                                else {
                                    val name = readVar(i)
                                    sb.append(env[name] ?: System.getenv(name) ?: "")
                                }
                            }
                            else -> { sb.append(ch); i++ }
                        }
                    }
                    val text = sb.toString()
                    // 引号内的通配符不展开：加 raw 标记（以引号包裹还原）
                    tokens.add(Token(Kind.WORD, text, "'" + text.replace("'", "'\\''") + "'"))
                }
                else -> {
                    val rawStart = i
                    val sb = StringBuilder()
                    var hadEsc = false
                    while (i < n) {
                        val ch = line[i]
                        if (ch.isWhitespace() || ch == '|' || ch == ';' || ch == '>' || ch == '#') break
                        if (ch == '$' && i + 1 < n && (line[i + 1] == '{' || line[i + 1].isLetterOrDigit() || line[i + 1] == '_')) {
                            val name = readVar(i)
                            sb.append(env[name] ?: System.getenv(name) ?: "")
                        } else if (ch == '\\') {
                            i++
                            if (i < n) { sb.append(line[i]); hadEsc = true; i++ }
                        } else { sb.append(ch); i++ }
                    }
                    val text = sb.toString()
                    val raw = if (hadEsc) "'" + text.replace("'", "'\\''") + "'" else line.substring(rawStart, i)
                    tokens.add(Token(Kind.WORD, text, raw))
                }
            }
        }
        return tokens
    }

    /** 把通配符词展开为文件系统匹配（无匹配则保留原文）。
     *  前一词为 =~ / -name / -match 时跳过展开（该词是模式参数，不是文件路径）。 */
    fun globExpand(tokens: List<Token>, cwd: java.io.File): List<Token> {
        val out = ArrayList<Token>()
        for (t in tokens) {
            if (t.kind != Kind.WORD || t.raw.startsWith("'")) { out.add(t); continue }
            val prev = out.lastOrNull()
            val isPatternArg = prev?.kind == Kind.WORD && prev.text in setOf("=~", "-name", "-match", "matches")
            if (isPatternArg) { out.add(t); continue }
            val text = t.text
            if (!text.contains('*') && !text.contains('?')) { out.add(t); continue }
            val dir: java.io.File
            val pattern: String
            val slash = text.lastIndexOf('/')
            if (slash >= 0) {
                dir = java.io.File(cwd, text.substring(0, slash))
                pattern = text.substring(slash + 1)
            } else { dir = cwd; pattern = text }
            if (!dir.isDirectory) { out.add(t); continue }
            val re = globToRegex(pattern)
            val matches = dir.listFiles()?.filter { re.matches(it.name) }?.map { it.name }?.sorted()
            if (matches.isNullOrEmpty()) out.add(t)
            else matches.forEach { m ->
                val full = if (slash >= 0) text.substring(0, slash + 1) + m else m
                out.add(Token(Kind.WORD, full, "'$full'"))
            }
        }
        return out
    }

    fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        for (c in glob) when (c) {
            '*' -> sb.append("[^/]*")
            '?' -> sb.append("[^/]")
            '.', '(', ')', '+', '[', ']', '{', '}', '^', '$', '\\', '|' -> sb.append('\\').append(c)
            else -> sb.append(c)
        }
        return Regex(sb.toString())
    }
}
