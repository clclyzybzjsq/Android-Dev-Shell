package dev.ads.shell.dsh

import android.content.Context
import java.io.File

/**
 * dsh 运行时闭包管理器。
 *
 * 闭包来源：dsh 仓库 `python/sdk-runtime` 的 `pnpm deploy --prod` 产物
 * （经 scripts/pack-runtime.sh 裁剪打包为 assets/dsh-runtime/runtime.tgz）。
 * 首次启动或版本变更时解压到 filesDir/dsh-runtime/，并由 `dsh` cmdlet
 * 以 libnode.so（nativeLibraryDir，来自 node-android CI）启动。
 */
object DshRuntime {

    const val VERSION_ASSET = "dsh-runtime/VERSION"
    const val RUNTIME_ASSET = "dsh-runtime/runtime.tgz"
    const val RUNTIME_DIR = "dsh-runtime"
    const val DSH_HOME = "dsh-home"

    enum class Status { READY, NEEDS_EXTRACT, NODE_MISSING, CLOSURE_MISSING, EXTRACTING }

    data class State(
        val status: Status,
        val nodePath: String?,
        val closureDir: String?,
        val installedVersion: String?,
        val assetVersion: String?,
    )

    fun nodePath(ctx: Context): String? {
        val f = File(ctx.applicationInfo.nativeLibraryDir, "libnode.so")
        return if (f.canExecute()) f.absolutePath else null
    }

    fun runtimeRoot(ctx: Context): File = File(ctx.filesDir, RUNTIME_DIR)

    fun closureEntry(ctx: Context): File? {
        val f = File(runtimeRoot(ctx), "node_modules/@deepseek-ai/dsh/lib/bin.js")
        return if (f.isFile) f else null
    }

    fun installedVersion(ctx: Context): String? {
        val f = File(runtimeRoot(ctx), "VERSION")
        return if (f.isFile) f.readText().trim() else null
    }

    fun assetVersion(ctx: Context): String? = try {
        ctx.assets.open(VERSION_ASSET).bufferedReader().use { it.readText().trim() }
    } catch (_: Exception) { null }

    fun assetExists(ctx: Context): Boolean = try {
        ctx.assets.open(RUNTIME_ASSET).close()
        true
    } catch (_: Exception) { false }

    fun state(ctx: Context): State {
        val node = nodePath(ctx)
        val entry = closureEntry(ctx)
        val aVer = assetVersion(ctx)
        val iVer = installedVersion(ctx)
        return when {
            node == null -> State(Status.NODE_MISSING, null, entry?.absolutePath, iVer, aVer)
            !assetExists(ctx) && entry == null -> State(Status.CLOSURE_MISSING, node, null, iVer, aVer)
            entry != null && (aVer == null || aVer == iVer) ->
                State(Status.READY, node, entry.absolutePath, iVer, aVer)
            else -> State(Status.NEEDS_EXTRACT, node, entry?.absolutePath, iVer, aVer)
        }
    }

    /** 解压 assets 闭包到 filesDir（增量：仅当资产版本与已装版本不同）。 */
    fun installIfNeeded(ctx: Context, log: (String) -> Unit = {}): Boolean {
        if (!assetExists(ctx)) { log("assets 中没有 dsh-runtime/runtime.tgz"); return false }
        val aVer = assetVersion(ctx)
        if (aVer != null && aVer == installedVersion(ctx)) return true

        val root = runtimeRoot(ctx)
        val tmp = File(ctx.filesDir, "$RUNTIME_DIR.extracting")
        tmp.deleteRecursively()
        tmp.mkdirs()
        log("解压 dsh 运行时闭包 (v${aVer ?: "?"}) ...")
        ctx.assets.open(RUNTIME_ASSET).use { input ->
            val proc = ProcessBuilder("tar", "xzf", "-")
                .directory(tmp)
                .start()
            input.copyTo(proc.outputStream)
            proc.outputStream.close()
            val code = proc.waitFor()
            if (code != 0) throw IllegalStateException("tar 解压失败 exit=$code")
        }
        if (aVer != null) File(tmp, "VERSION").writeText(aVer)
        // 原子替换：旧目录改名、新目录就位、旧目录删除
        val old = File(ctx.filesDir, "$RUNTIME_DIR.old")
        old.deleteRecursively()
        if (root.exists()) root.renameTo(old)
        if (!tmp.renameTo(root)) throw IllegalStateException("运行时就位失败")
        old.deleteRecursively()
        // 闭包内全部可执行标记（tar 在 Windows/打包端可能丢失 x 位）
        root.walkTopDown().forEach { if (it.isFile) it.setReadable(true, true) }
        log("dsh 运行时就绪: ${root.absolutePath}")
        return true
    }
}
