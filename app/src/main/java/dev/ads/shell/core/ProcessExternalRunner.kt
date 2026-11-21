package dev.ads.shell.core

import java.io.File

/**
 * 管道内外部命令执行：ProcessBuilder 直连 argv（不经 shell 二次解析），
 * stdin 流入内置命令渲染的文本行，stdout/stderr 按行转为 JStr。
 */
class ProcessExternalRunner : ExternalRunner {

    override fun pipe(argv: List<String>, input: Sequence<JValue>, ctx: Ctx): Sequence<JValue> = sequence {
        val pb = ProcessBuilder(argv)
        pb.directory(ctx.cwd)
        val env = pb.environment()
        ctx.env.forEach { (k, v) -> env[k] = v }

        val proc = try { pb.start() } catch (e: Exception) {
            ctx.error("${argv.first()}: ${e.message ?: "无法执行"}")
            ctx.lastExit = 127
            return@sequence
        }

        // 非交互场景默认丢弃 stdin，除非上游有数据
        val writerThread = Thread {
            try {
                proc.outputStream.bufferedWriter(Charsets.UTF_8).use { w ->
                    for (v in input) {
                        w.write(v.toLine())
                        w.write("\n")
                    }
                }
            } catch (_: Exception) {}
        }
        writerThread.isDaemon = true
        writerThread.start()

        // stdout 与 stderr 并行读取，防止缓冲塞满死锁
        val errLines = ArrayList<String>()
        val errThread = Thread {
            try {
                proc.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    synchronized(errLines) { errLines.add(line) }
                }
            } catch (_: Exception) {}
        }
        errThread.isDaemon = true
        errThread.start()

        val reader = proc.inputStream.bufferedReader(Charsets.UTF_8)
        try {
            for (line in reader.lineSequence()) yield(JValue.JStr(line))
            reader.close()
        } finally {
            proc.waitFor()
            errThread.join(1000)
            writerThread.join(1000)
        }

        synchronized(errLines) {
            errLines.forEach { ctx.err(it) }
        }

        ctx.lastExit = proc.exitValue()
    }

    override fun interactive(argv: List<String>, ctx: Ctx): Boolean = false
}
