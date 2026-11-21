package dev.ads.shell.core

import java.io.File

/** Android 能力桥：解释器只依赖此接口，UI 层注入实现（单测可注入 stub） */
interface AndroidBridge {
    fun clipboardGet(): String?
    fun clipboardSet(text: String)
    fun notifyUser(title: String, body: String): Boolean
    fun openUri(uri: String): Boolean
    fun listApps(): List<Pair<String, String>> // (label, packageName)
    fun deviceInfo(): LinkedHashMap<String, JValue>
    fun battery(): LinkedHashMap<String, JValue>
    fun shizukuConnected(): Boolean
    /** 在 Shizuku（adb shell, uid 2000）下执行命令并返回合并输出；未连接时抛 IllegalStateException */
    fun shizukuExec(command: String): String
}

object NoOpBridge : AndroidBridge {
    override fun clipboardGet(): String? = null
    override fun clipboardSet(text: String) {}
    override fun notifyUser(title: String, body: String) = false
    override fun openUri(uri: String) = false
    override fun listApps() = emptyList<Pair<String, String>>()
    override fun deviceInfo() = linkedMapOf<String, JValue>("note" to JValue.JStr("bridge unavailable"))
    override fun battery() = linkedMapOf<String, JValue>("note" to JValue.JStr("bridge unavailable"))
    override fun shizukuConnected() = false
    override fun shizukuExec(command: String): String =
        throw IllegalStateException("Shizuku 未连接")
}

/** 外部命令执行器 */
interface ExternalRunner {
    /**
     * 在管道中执行外部命令：stdin 取自 input 渲染的文本行，stdout 按行产出 JStr。
     * 返回退出码经 Ctx.lastExit 记录。
     */
    fun pipe(argv: List<String>, input: Sequence<JValue>, ctx: Ctx): Sequence<JValue>

    /** 独立执行（可能是交互式程序），默认不支持时抛异常由调用方处理 */
    fun interactive(argv: List<String>, ctx: Ctx) = false
}

class ExitShellException(val code: Int) : Exception("exit $code")

/**
 * 解释器上下文。
 */
class Ctx(
    val env: MutableMap<String, String>,
    var cwd: File,
    val out: (String) -> Unit,
    val err: (String) -> Unit,
    val bridge: AndroidBridge,
    val external: ExternalRunner,
) {
    var lastExit = 0
    internal var exitRequested = false

    fun resolve(path: String): File {
        val f = File(path)
        return if (f.isAbsolute) f else File(cwd, path)
    }

    fun error(msg: String) { err("ads: $msg") }
}

/** 内置命令基类 */
abstract class Cmdlet(val name: String, val help: String) {
    open fun usage(): String = "用法: $name $help"
    abstract fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx): Sequence<JValue>
}

class CmdletRegistry {
    private val map = LinkedHashMap<String, Cmdlet>()
    private val aliases = HashMap<String, String>()

    fun register(c: Cmdlet) { map[c.name] = c }
    fun alias(alias: String, target: String) { aliases[alias] = target }

    fun get(name: String): Cmdlet? = map[name] ?: aliases[name]?.let { map[it] }
    fun all(): Collection<Cmdlet> = map.values
    fun isBuiltin(name: String): Boolean = get(name) != null
}
