package dev.ads.shell.core

/**
 * DshShell 的值系统：一切在管道中流转的数据都是 JValue（JSON 同构）。
 * 设计对齐 PowerShell 对象管道 / nushell 结构化管道，但以 JSON 为唯一交换格式，
 * 使 coding agent 可以无歧义地序列化/消费任何命令输出。
 */
sealed class JValue {
    object JNull : JValue()
    data class JBool(val v: Boolean) : JValue()
    data class JLong(val v: Long) : JValue()
    data class JDouble(val v: Double) : JValue()
    data class JStr(val v: String) : JValue()
    data class JArr(val items: List<JValue>) : JValue()
    data class JObj(val fields: LinkedHashMap<String, JValue>) : JValue()

    val typeName: String
        get() = when (this) {
            is JNull -> "null"; is JBool -> "bool"; is JLong -> "int"
            is JDouble -> "float"; is JStr -> "string"; is JArr -> "list"; is JObj -> "object"
        }

    val truthy: Boolean
        get() = when (this) {
            is JNull -> false
            is JBool -> v
            is JLong -> v != 0L
            is JDouble -> v != 0.0
            is JStr -> v.isNotEmpty()
            is JArr -> items.isNotEmpty()
            is JObj -> fields.isNotEmpty()
        }

    /** 进入文本管道（外部命令 stdin）时的渲染：字符串保持原文，其余转 JSON 文本 */
    fun toLine(): String = when (this) {
        is JStr -> v
        else -> Json.write(this)
    }

    override fun toString(): String = toDisplay()

    fun toDisplay(): String = when (this) {
        is JStr -> v
        else -> Json.write(this, pretty = true)
    }

    fun asLongOrNull(): Long? = when (this) {
        is JLong -> v; is JDouble -> v.toLong(); is JStr -> v.toLongOrNull(); else -> null
    }

    fun asDoubleOrNull(): Double? = when (this) {
        is JLong -> v.toDouble(); is JDouble -> v; is JStr -> v.toDoubleOrNull(); else -> null
    }

    fun field(name: String): JValue? = (this as? JObj)?.fields?.get(name)

    companion object {
        fun of(v: Any?): JValue = when (v) {
            null -> JNull
            is Boolean -> JBool(v)
            is Int -> JLong(v.toLong())
            is Long -> JLong(v)
            is Float -> JDouble(v.toDouble())
            is Double -> JDouble(v)
            is String -> JStr(v)
            is List<*> -> JArr(v.map { of(it) })
            is Map<*, *> -> JObj(LinkedHashMap<String, JValue>().apply {
                v.forEach { (k, value) -> put(k.toString(), of(value)) }
            })
            else -> JStr(v.toString())
        }
    }
}

// ---------------------------------------------------------------------------
// JSON 解析/序列化（零依赖手写，JVM 单测与 Android 均可用）
// ---------------------------------------------------------------------------

object Json {

    class JsonException(msg: String) : Exception(msg)

    fun write(v: JValue, pretty: Boolean = false): String {
        val sb = StringBuilder()
        writeValue(sb, v, pretty, 0)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, v: JValue, pretty: Boolean, depth: Int) {
        when (v) {
            is JValue.JNull -> sb.append("null")
            is JValue.JBool -> sb.append(if (v.v) "true" else "false")
            is JValue.JLong -> sb.append(v.v.toString())
            is JValue.JDouble -> {
                val d = v.v
                if (d.isNaN() || d.isInfinite()) sb.append("null") else sb.append(d.toString())
            }
            is JValue.JStr -> writeString(sb, v.v)
            is JValue.JArr -> {
                if (v.items.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                v.items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    if (pretty) { sb.append('\n'); indent(sb, depth + 1) }
                    writeValue(sb, item, pretty, depth + 1)
                }
                if (pretty) { sb.append('\n'); indent(sb, depth) }
                sb.append(']')
            }
            is JValue.JObj -> {
                if (v.fields.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var i = 0
                for ((k, value) in v.fields) {
                    if (i++ > 0) sb.append(',')
                    if (pretty) { sb.append('\n'); indent(sb, depth + 1) }
                    writeString(sb, k)
                    sb.append(':')
                    if (pretty) sb.append(' ')
                    writeValue(sb, value, pretty, depth + 1)
                }
                if (pretty) { sb.append('\n'); indent(sb, depth) }
                sb.append('}')
            }
        }
    }

    private fun indent(sb: StringBuilder, depth: Int) { repeat(depth) { sb.append("  ") } }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append('"')
    }

    fun parse(text: String): JValue {
        val p = Parser(text)
        val v = p.parseValue()
        p.skipWs()
        if (!p.atEnd()) throw JsonException("JSON 尾部有多余内容 @${p.pos}")
        return v
    }

    private class Parser(val s: String) {
        var pos = 0
        fun atEnd() = pos >= s.length
        fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }

        fun parseValue(): JValue {
            skipWs()
            if (atEnd()) throw JsonException("JSON 意外结束")
            return when (val c = s[pos]) {
                '{' -> parseObj()
                '[' -> parseArr()
                '"' -> JValue.JStr(parseString())
                't' -> { expect("true"); JValue.JBool(true) }
                'f' -> { expect("false"); JValue.JBool(false) }
                'n' -> { expect("null"); JValue.JNull }
                else -> if (c == '-' || c.isDigit()) parseNumber()
                        else throw JsonException("JSON 非法字符 '$c' @$pos")
            }
        }

        fun expect(word: String) {
            if (!s.startsWith(word, pos)) throw JsonException("JSON 非法字面量 @$pos")
            pos += word.length
        }

        fun parseObj(): JValue.JObj {
            pos++ // {
            val m = LinkedHashMap<String, JValue>()
            skipWs()
            if (!atEnd() && s[pos] == '}') { pos++; return JValue.JObj(m) }
            while (true) {
                skipWs()
                if (atEnd() || s[pos] != '"') throw JsonException("JSON 对象键必须是字符串 @$pos")
                val k = parseString()
                skipWs()
                if (atEnd() || s[pos] != ':') throw JsonException("JSON 缺少 ':' @$pos")
                pos++
                m[k] = parseValue()
                skipWs()
                if (atEnd()) throw JsonException("JSON 对象未闭合")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return JValue.JObj(m) }
                    else -> throw JsonException("JSON 对象分隔符错误 @$pos")
                }
            }
        }

        fun parseArr(): JValue.JArr {
            pos++ // [
            val items = ArrayList<JValue>()
            skipWs()
            if (!atEnd() && s[pos] == ']') { pos++; return JValue.JArr(items) }
            while (true) {
                items.add(parseValue())
                skipWs()
                if (atEnd()) throw JsonException("JSON 数组未闭合")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return JValue.JArr(items) }
                    else -> throw JsonException("JSON 数组分隔符错误 @$pos")
                }
            }
        }

        fun parseString(): String {
            pos++ // "
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonException("JSON 字符串未闭合")
                when (val c = s[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw JsonException("JSON 转义序列未闭合")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonException("JSON \\u 转义不完整")
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw JsonException("JSON 非法转义 '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun parseNumber(): JValue {
            val start = pos
            if (!atEnd() && s[pos] == '-') pos++
            var isDouble = false
            while (!atEnd() && (s[pos].isDigit() || s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E'
                        || s[pos] == '+' || s[pos] == '-')) {
                if (s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E') isDouble = true
                pos++
            }
            val text = s.substring(start, pos)
            return if (isDouble) JValue.JDouble(text.toDouble())
                   else text.toLongOrNull()?.let { JValue.JLong(it) }
                        ?: JValue.JDouble(text.toDouble())
        }
    }
}
