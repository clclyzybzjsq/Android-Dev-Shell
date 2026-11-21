# node-android — Node.js 交叉编译（Android arm64）

从 Termux nodejs 打包配方（MIT, termux/termux-packages）改编，为 ADS 产出
可内嵌的 Android 原生 Node 运行时。

与 Termux 的差异：**全部使用 Node 自带静态依赖**（openssl/zlib/cares/sqlite/small-icu），
产物自包含、无 termux prefix、无外部共享库 —— 直接适配 APK 嵌入
（PIE 可执行文件改名 `libnode.so` 放入 APK 的 `lib/arm64-v8a/`，安装后从
`nativeLibraryDir` 执行，符合 targetSdk>=29 的 W^X 合规与 16KB 页对齐）。

## 用法

```
NODE_VERSION=26.13.1 ANDROID_NDK_LATEST_HOME=<ndk> bash build.sh
# 输出: out/libnode.so
```

或直接跑 GitHub Actions：`.github/workflows/build-node-android.yml`（workflow_dispatch）。

## 产出物在 ADS 中的角色

| 产物 | 位置 | 作用 |
|---|---|---|
| `libnode.so` | APK `lib/arm64-v8a/` | dsh 运行时宿主（Node）|
| dsh 运行时闭包 | APK assets，首启解压到 filesDir | `python/sdk-runtime` deploy 树（见 dsh 仓库 python/sdk-runtime）|
| `node-addon-require-builtin` | CI 中随 libnode 头文件交叉编译 | dsh-app-boot 的启动必需原生模块 |

## 注意

- termux 补丁针对 node 26.13.1；换版本需核对补丁
- `--with-intl=small-icu`（内置 ICU，仅完整英语数据）；如需完整 ICU 再评估 system-icu 方案
- 首次构建约 60–120 分钟（V8 编译占大头）
