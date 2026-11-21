package dev.ads.shell.cmdlets

import dev.ads.shell.core.Cmdlet
import dev.ads.shell.core.CmdletRegistry
import dev.ads.shell.core.Ctx
import dev.ads.shell.core.Json
import dev.ads.shell.core.JValue

class PipeCmdlets {
    companion object {
        fun registerAll(r: CmdletRegistry) {
            r.register(FromJson()); r.register(ToJson()); r.register(Lines()); r.register(Split())
            r.register(Join()); r.register(Where()); r.register(Select()); r.register(Sort())
            r.register(Count()); r.register(First()); r.register(Last()); r.register(Top())
            r.register(Format()); r.register(Uniq()); r.register(Flatten()); r.register(Keys())
        }

        class FromJson : Cmdlet("fromjson", "把输入文本按 JSON 解析；数组自动展开为对象流") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                val text = input.joinToString("\n") { it.toLine() }
                val v = try { Json.parse(text) } catch (e: Exception) {
                    ctx.error("fromjson: ${e.message}"); return@sequence
                }
                if (v is JValue.JArr) v.items.forEach { yield(it) } else yield(v)
            }
        }

        class ToJson : Cmdlet("tojson", "[-c] 把输入序列化为 JSON（-c 紧凑单行）") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                val compact = "-c" in args
                val all = input.toList()
                val v = if (all.size == 1) all[0] else JValue.JArr(all)
                yield(JValue.JStr(Json.write(v, pretty = !compact)))
            }
        }

        class Lines : Cmdlet("lines", "把多值输入合并成单个字符串再按行拆分") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                val text = input.joinToString("\n") { it.toLine() }
                if (text.isNotEmpty()) text.split('\n').forEach { yield(JValue.JStr(it)) }
            }
        }

        class Split : Cmdlet("split", "[-f 字段名] [-n 字段名] <分隔符>  拆分字符串为字段") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                val sep = args.firstOrNull { !it.startsWith("-") } ?: " "
                val fields = args.indexOf("-f").takeIf { it >= 0 }?.let { args[it + 1].split(',') } ?: emptyList()
                val numeric = "-n" in args
                for (v in input) {
                    val parts = v.toLine().split(sep)
                    if (fields.isEmpty() && !numeric) {
                        // 无字段名：产出 name=1..N 的对象
                        val m = LinkedHashMap<String, JValue>()
                        parts.forEachIndexed { i, p -> m["${i + 1}"] = JValue.JStr(p) }
                        yield(JValue.JObj(m))
                    } else {
                        val names = fields.ifEmpty { parts.indices.map { "f${it}" } }
                        val m = LinkedHashMap<String, JValue>()
                        parts.forEachIndexed { i, p ->
                            val key = names.getOrNull(i) ?: "f$i"
                            val value: JValue = if (numeric) p.toLongOrNull()?.let { JValue.JLong(it) }
                                ?: JValue.JStr(p) else JValue.JStr(p)
                            m[key] = value
                        }
                        yield(JValue.JObj(m))
                    }
                }
            }
        }

        class Join : Cmdlet("join", "<分隔符>  把输入合并为一行文本") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) =
                sequenceOf(JValue.JStr(input.joinToString(args.firstOrNull() ?: " ") { it.toLine() }))
        }

        class Where : Cmdlet("where", "[-v] <字段> <op> <值>  op: == != >= <= gt lt contains startswith endswith =~（> 与 < 已被重定向占用，请用 gt/lt 或 >=/<=）") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                val negate = "-v" in args || "-not" in args
                val cond = args.filter { !it.startsWith("-") }
                if (cond.size < 2) { ctx.error(usage()); return@sequence }

                if (cond.size == 2 && (cond[1] == "truthy" || cond[1] == "exists")) {
                    for (v in input) {
                        val f = v.field(cond[0])
                        val keep = f != null && f != JValue.JNull && f.truthy
                        if (keep != negate) yield(v)
                    }
                    return@sequence
                }

                val field = cond[0]; val op = cond[1]
                val rhsRaw = cond.drop(2).joinToString(" ")

                fun cmp(v: JValue, rhs: JValue?): Int {
                    val a = v.asDoubleOrNull(); val b = rhs?.asDoubleOrNull()
                    if (a != null && b != null) return a.compareTo(b)
                    val as_ = v.toLine(); val bs = rhs?.toLine() ?: rhsRaw
                    return as_.compareTo(bs)
                }

                for (v in input) {
                    val f = v.field(field) ?: JValue.JNull
                    val keep = when (op) {
                        "==", "eq" -> f.toLine() == rhsRaw
                        "!=", "ne" -> f.toLine() != rhsRaw
                        ">", "gt" -> cmp(f, coerce(rhsRaw, f)) > 0
                        ">=", "ge" -> cmp(f, coerce(rhsRaw, f)) >= 0
                        "<", "lt" -> cmp(f, coerce(rhsRaw, f)) < 0
                        "<=", "le" -> cmp(f, coerce(rhsRaw, f)) <= 0
                        "contains" -> f.toLine().contains(rhsRaw)
                        "startswith" -> f.toLine().startsWith(rhsRaw)
                        "endswith" -> f.toLine().endsWith(rhsRaw)
                        "=~" -> dev.ads.shell.core.Lexer.globToRegex(rhsRaw).matches(f.toLine())
                        else -> { ctx.error("where: 未知运算符 $op"); return@sequence }
                    }
                    if (keep != negate) yield(v)
                }
            }

            fun coerce(raw: String, ref: JValue): JValue {
                if (ref.asDoubleOrNull() != null) return raw.toLongOrNull()?.let { JValue.JLong(it) }
                    ?: JValue.JDouble(raw.toDoubleOrNull() ?: 0.0)
                return JValue.JStr(raw)
            }
        }

        class Select : Cmdlet("select", "<字段...>  投影对象字段；支持 a.b 嵌套") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                val fields = args.filter { !it.startsWith("-") }
                if (fields.isEmpty()) { ctx.error(usage()); return@sequence }
                for (v in input) {
                    val m = LinkedHashMap<String, JValue>()
                    for (path in fields) {
                        val parts = path.split('.')
                        var cur: JValue? = v
                        for (p in parts) { cur = cur?.field(p) }
                        val key = parts.last()
                        m[key] = cur ?: JValue.JNull
                    }
                    yield(JValue.JObj(m))
                }
            }
        }

        class Sort : Cmdlet("sort", "[-d] [字段]  -d 降序；数值字段按数值排序") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                val desc = "-d" in args || "-r" in args
                val field = args.firstOrNull { !it.startsWith("-") }
                val all = input.toList()
                val sorted = all.sortedWith { a, b ->
                    val x = field?.let { a.field(it) } ?: a
                    val y = field?.let { b.field(it) } ?: b
                    val xn = x.asDoubleOrNull(); val yn = y.asDoubleOrNull()
                    val r = if (xn != null && yn != null) xn.compareTo(yn) else x.toLine().compareTo(y.toLine())
                    if (desc) -r else r
                }
                sorted.forEach { yield(it) }
            }
        }

        class Count : Cmdlet("count", "统计输入条数") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) =
                sequenceOf(JValue.JLong(input.count().toLong()))
        }

        class First : Cmdlet("first", "[n]") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                val n = args.firstOrNull()?.toIntOrNull() ?: 1
                input.take(n).forEach { yield(it) }
            }
        }

        class Last : Cmdlet("last", "[n]") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                val n = args.firstOrNull()?.toIntOrNull() ?: 1
                input.toList().takeLast(n).forEach { yield(it) }
            }
        }

        class Top : Cmdlet("top", "[-d 字段] <n>  取前 n 条") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                val di = args.indexOf("-d")
                val n = args.firstOrNull { !it.startsWith("-") && it != args.getOrNull(di + 1) }?.toIntOrNull() ?: 10
                val field = di.takeIf { it >= 0 }?.let { args[it + 1] }
                val sorted = input.toList().sortedByDescending { v ->
                    field?.let { v.field(it)?.asDoubleOrNull() } ?: v.asDoubleOrNull() ?: Double.NEGATIVE_INFINITY
                }
                sorted.take(n).forEach { yield(it) }
            }
        }

        class Format : Cmdlet("format", "<模板>  {name} {0} 引用字段") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                val tpl = args.joinToString(" ")
                for (v in input) {
                    val text = Regex("\\{(\\w+)\\}").replace(tpl) { m ->
                        v.field(m.groupValues[1])?.toLine() ?: m.value
                    }
                    yield(JValue.JStr(text))
                }
            }
        }

        class Uniq : Cmdlet("uniq", "按整值去重（保持顺序）") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                val seen = HashSet<String>()
                for (v in input) { val k = Json.write(v); if (seen.add(k)) yield(v) }
            }
        }

        class Flatten : Cmdlet("flatten", "把数组值展开为对象流") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                for (v in input) {
                    if (v is JValue.JArr) v.items.forEach { yield(it) } else yield(v)
                }
            }
        }

        class Keys : Cmdlet("keys", "列出对象的字段名") {
            override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
                for (v in input) {
                    if (v is JValue.JObj) v.fields.keys.forEach { yield(JValue.JStr(it)) }
                }
            }
        }
    }
}
