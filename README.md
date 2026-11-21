# ADS — Android Dev Shell

无 root、无虚拟机的安卓原生命令行环境：PTY 交互终端 + JSON 对象管道 shell，
内置 Android API 命令与 Shizuku 特权层。面向两类使用者：需要在手机上跑命令的人类，
以及需要结构化、可解析输出的 coding agent。

当前版本 0.1.0。

## 特性

- **原生执行**：NDK 编译的 bionic ELF 直接运行于 Android 宿主内核；无 proot、无容器、无 root 依赖
- **PTY 终端**：完整 VT 模拟（vendored termux terminal-emulator/view，Apache-2.0），支持 vim/htop 等 TUI 程序；底部扩展按键行（ESC/TAB/粘滞 CTRL/ALT/方向键/粘贴，可横滑）
- **对象管道**：管道中流转 JSON 同构值（null/bool/int/float/string/list/object），字符串保持原文、对象自动 JSON 化；内置与外部命令双向桥接
- **30 个内置命令** + 系统 toybox 全量外部命令直通
- **Android API 命令**：apps/battery/device/clip/notify/open，单应用实现，无需 termux-api 配套
- **特权分层**：Shizuku（adb shell, uid 2000）执行 pm/am/settings/dumpsys/logcat，非 root 可用
- **agent 接口**：解释器为纯 Kotlin 库可内嵌；REPL 支持 adb 注入脚本；输出强制结构化
- **Play 合规路径**：APKLF（lib*.so + extractNativeLibs）、specialUse 前台服务、NDK 27.2 16KB 页对齐

## 命令语义

管道传输对象而非字节流。内置命令产出对象流，外部命令经 stdin/stdout 按行桥接。

```
ls | where size gt 1000 | sort -d size | select name size
echo [3,1,2] | fromjson | sort | tojson -c          # [1,2,3]
apps | where label =~ *termux* | count
http -j https://httpbin.org/json | select body.slideshow.title
shexec pm list packages                              # 经 Shizuku，uid 2000
```

要点：

- 比较运算符为 `gt lt ge le eq ne` 或 `>= <= == !=`；`>` `<` 保留 POSIX 重定向语义
- `where <字段> <op> <值>`，支持 contains/startswith/endswith/=~（glob）；`-v` 取反
- 变量 `$VAR`/`${VAR}`；通配符 `*` `?` 按目录展开（`=~`、`-name` 后的词视为模式）
- 20 个 PowerShell 高频 Verb-Noun 命令已映射为别名（Where-Object、Get-ChildItem、ConvertTo-Json 等）
- `help [命令]` 查看用法；`which <命令>` 区分内置/外部

## 内置命令清单

| 分组 | 命令 |
|---|---|
| 核心 | echo cd pwd env setenv unset exit help which sh |
| 文件 | ls cat head tail mkdir rm cp mv touch find stat wc |
| 管道 | fromjson tojson lines split join where select sort count first last top format uniq flatten keys |
| 网络 | http dns |
| Android | apps battery device clip notify open |
| 特权 | shz shexec |

## 构建

依赖：JDK 17、Android SDK（platform 34 + build-tools）、NDK 27.2+。

```
./gradlew assembleDebug        # 产物: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # 解释器语义单测
```

如需通过代理拉取依赖，请在用户级 `~/.gradle/gradle.properties` 中配置 `systemProp.https.proxyHost/Port`，不要提交到仓库。

## 安装与运行

1. 安装 APK，启动后即为交互终端（登录 shell）
2. 顶栏「管道控制台」进入对象管道 REPL
3. 特权命令：安装并启动 [Shizuku](https://github.com/RikkaApps/Shizuku)（无线调试即可，无需 root），在 `shz` 查看状态并授权，之后 `shexec <命令>` 可用

### adb 驱动 REPL（agent / 自动化入口）

```
adb shell "am start -n dev.ads.shell/.PipeConsoleActivity --es autorun 'ls | count; device | select model'"
```

### Phantom Process Killer（Android 12+ 注意）

系统默认限制每应用 32 个子进程，超出会被 SIGKILL。缓解（需 adb 或本应用 Shizuku）：

```
adb shell settings put global settings_enable_monitor_phantom_procs false
```

## 架构

```
UI:  MainActivity(交互终端) / PipeConsoleActivity(REPL)
服务: TermService —— specialUse 前台服务，托管 PTY 会话
解释器: core/ —— Lexer/Parser → Pipeline → CmdletRegistry (纯 Kotlin)
终端: terminal-emulator + terminal-view (vendored, Apache-2.0) + JNI(/dev/ptmx)
特权: Shizuku UserService (AIDL IShellService, uid 2000)
```

## 路线图

- unix socket 常驻接口（agent 正式接线）
- quickjs-ng 嵌入（脚本块能力）
- uutils coreutils 子集打包
- 会话多标签与恢复

## 许可

MIT，见 [LICENSE](LICENSE)。
`terminal-emulator/` 与 `terminal-view/` 来自 [termux-app](https://github.com/termux/termux-app)，Apache License 2.0。
