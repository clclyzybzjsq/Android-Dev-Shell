package dev.ads.shell

import dev.ads.shell.core.Interpreter
import dev.ads.shell.core.JValue
import dev.ads.shell.core.NoOpBridge
import dev.ads.shell.core.Ctx
import dev.ads.shell.core.ProcessExternalRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class InterpreterTest {

    lateinit var ctx: Ctx
    lateinit var out: StringBuilder
    val tmp = File(System.getProperty("java.io.tmpdir"), "ads-test-${System.nanoTime()}")

    @Before
    fun setup() {
        tmp.mkdirs()
        out = StringBuilder()
        ctx = Ctx(
            env = mutableMapOf("HOME" to tmp.absolutePath, "PATH" to "/usr/bin:/bin"),
            cwd = tmp,
            out = { out.append(it) },
            err = { out.append("[err] ").append(it) },
            bridge = NoOpBridge,
            external = ProcessExternalRunner(),
        )
        Interpreter.defaultRegistry()
    }

    private fun run(line: String) {
        Interpreter(Interpreter.defaultRegistry()).runLine(line, ctx)
    }

    private fun jsonOut(): List<JValue> {
        // 控制台输出是多行 JSON/文本；单值测试常用 tojson -c，这里解析最后一行
        return out.toString().trim().lines().map { dev.ads.shell.core.Json.parse(it) }
    }

    @Test
    fun `echo json pipeline`() {
        run("""echo {"a":1,"b":"x"} | fromjson | select b""")
        assertTrue(out.toString().contains(""""b": "x""""))
    }

    @Test
    fun `where filter on objects`() {
        run("""echo [{"n":1},{"n":50},{"n":100}] | fromjson | where n gt 10 | tojson -c""")
        assertEquals("""[{"n":50},{"n":100}]""", out.toString().trim())
    }

    @Test
    fun `sort and count`() {
        run("""echo [{"s":30},{"s":1},{"s":200}] | fromjson | sort s | select s | tojson -c""")
        assertEquals("""[{"s":1},{"s":30},{"s":200}]""", out.toString().trim())
        run("""echo [1,2,3,4,5] | fromjson | count""")
        assertEquals("5", out.toString().trim().lines().last())
    }

    @Test
    fun `glob and file objects`() {
        File(tmp, "a.txt").writeText("hello")
        File(tmp, "b.txt").writeText("world!")
        run("""ls | where name =~ *.txt | select name | tojson -c""")
        val line = out.toString().trim().lines().last()
        assertTrue(line.contains("a.txt") && line.contains("b.txt"))
    }

    @Test
    fun `redirection works`() {
        File(tmp, "out.json").delete()
        run("""echo {"x":42} | fromjson | tojson -c > out.json""")
        assertEquals("""{"x":42}""", File(tmp, "out.json").readText().trim())
    }

    @Test
    fun `variable substitution`() {
        ctx.env["WHO"] = "ads"
        run("""echo hello ${'$'}WHO""")
        assertEquals("hello ads", out.toString().trim())
    }

    @Test
    fun `exit cmdlet stops shell`() {
        try {
            run("""exit 3""")
            throw AssertionError("应抛出 ExitShellException")
        } catch (e: dev.ads.shell.core.ExitShellException) {
            assertEquals(3, e.code)
        }
    }

    @Test
    fun `nested field access`() {
        run("""echo {"a":{"b":{"c":"deep"}}} | fromjson | select a.b.c""")
        assertTrue(out.toString().contains("deep"))
    }
}
