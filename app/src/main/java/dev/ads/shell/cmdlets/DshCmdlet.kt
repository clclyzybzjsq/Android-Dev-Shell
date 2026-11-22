package dev.ads.shell.cmdlets

import android.content.Context
import dev.ads.shell.core.Cmdlet
import dev.ads.shell.core.Ctx
import dev.ads.shell.core.JValue
import dev.ads.shell.dsh.DshRuntime
import java.io.File

/**
 * dsh（DeepSeek Harness）安卓内嵌运行时入口。
 *
 * 运行链路：assets 闭包（首次解压到 filesDir/dsh-runtime）+ libnode.so（nativeLibraryDir）
 * → node node_modules/@deepseek-ai/dsh/lib/bin.js --profile <名>
 * → 输出进入 ADS 管道；SDK 模式为 stdio JSON-RPC。
 *
 * 用法：
 *   dsh status                    查看运行时状态
 *   dsh install                   手动解压闭包
 *   dsh [--profile 名] [args...]  启动（默认 profile: sdk-minimal）
 */
class DshCmdlet : Cmdlet(
    "dsh",
    "[status|install] [--profile <名>] [args...]  内嵌 dsh 运行时（默认 profile: sdk-minimal）"
) {
    override fun run(args: List<String>, input: Sequence<JValue>, ctx: Ctx) = sequence<JValue> {
        val appCtx = ctx.host as? Context
        if (appCtx == null) {
            ctx.error("dsh: 需要 Android 环境（host 未注入）")
            return@sequence
        }

        fun stateRow(st: DshRuntime.State, extra: Map<String, JValue> = emptyMap()): JValue.JObj {
            val m = LinkedHashMap<String, JValue>()
            m["status"] = JValue.JStr(st.status.name)
            m["node"] = JValue.JStr(st.nodePath ?: "missing")
            m["closure"] = JValue.JStr(st.closureDir ?: "missing")
            m["installedVersion"] = JValue.JStr(st.installedVersion ?: "-")
            m["assetVersion"] = JValue.JStr(st.assetVersion ?: "-")
            extra.forEach { (k, v) -> m[k] = v }
            return JValue.JObj(m)
        }

        val sub = args.firstOrNull()
        if (sub == "status" || sub == "install") {
            if (sub == "install") {
                try {
                    val ok = DshRuntime.installIfNeeded(appCtx) { ctx.err(it) }
                    if (!ok) {
                        ctx.error("assets 中没有 dsh-runtime/runtime.tgz（等待 CI 打包后随 APK 分发）")
                        ctx.lastExit = 1
                        return@sequence
                    }
                } catch (e: Exception) {
                    ctx.error("install: ${e.message}")
                    ctx.lastExit = 1
                    return@sequence
                }
            }
            val st = DshRuntime.state(appCtx)
            yield(stateRow(st))
            ctx.lastExit = if (st.status == DshRuntime.Status.READY) 0 else 1
            return@sequence
        }

        val st = DshRuntime.state(appCtx)
        if (st.status == DshRuntime.Status.NEEDS_EXTRACT) {
            try { DshRuntime.installIfNeeded(appCtx) { ctx.err(it) } } catch (e: Exception) {
                ctx.error("闭包解压失败: ${e.message}")
                ctx.lastExit = 1
                return@sequence
            }
        }
        val st2 = DshRuntime.state(appCtx)
        if (st2.nodePath == null || st2.closureDir == null) {
            yield(stateRow(st2, mapOf("hint" to JValue.JStr(
                "libnode.so 来自 node-android CI artifact（放 nativeLibraryDir）；" +
                "闭包来自 pack-runtime 产物（assets/dsh-runtime/runtime.tgz + VERSION）"
            ))))
            ctx.lastExit = 1
            return@sequence
        }

        // 参数装配：dsh [--profile 名] [app-args...]
        val rest = args.drop(1).toMutableList()
        val profileIdx = rest.indexOf("--profile")
        val profile = if (profileIdx >= 0 && profileIdx + 1 < rest.size) {
            val p = rest[profileIdx + 1]
            rest.removeAt(profileIdx); rest.removeAt(profileIdx)
            p
        } else "sdk-minimal"

        val node = st2.nodePath!!
        val entry = st2.closureDir!!
        val dshHome = File(appCtx.filesDir, DshRuntime.DSH_HOME).apply { mkdirs() }

        ctx.env["DSH_HOME"] = dshHome.absolutePath
        ctx.env["HOME"] = dshHome.absolutePath
        val argv = listOf(node, entry, "--profile", profile) + rest
        for (v in ctx.external.pipe(argv, input, ctx)) yield(v)
    }
}
