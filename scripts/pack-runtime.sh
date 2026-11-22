#!/usr/bin/env bash
# 打包 dsh 运行时闭包 → ADS assets（app/src/main/assets/dsh-runtime/）
#
# 用法:
#   ./pack-runtime.sh <deploy闭包目录> [输出到ADS仓库assets目录]
#
# 输入: dsh 仓库 python/sdk-runtime 的 pnpm deploy --prod 产物
#       (deploy 时如有 ERR_PNPM_IGNORED_BUILDS, 手动执行
#        node_modules/.pnpm/@deepseek-ai+dsh-subprocess-local*/node_modules/
#        @deepseek-ai/dsh-subprocess-local/scripts/ensure-spawn-helper.mjs)
# 输出: assets/dsh-runtime/{runtime.tgz, VERSION}
#
# 裁剪策略（面向 android-arm64 目标）:
#   - 删除所有平台原生包 (win32/win/darwin/linux-x64/musl), android-arm64
#     原生模块(如 node-addon-require-builtin 的 android 变体)需 CI 交叉编译
#     后单独注入
#   - 删除各 workspace 包的 src/ tests/ 与 *.map, 保留 lib/ 与 node_modules
#   - libreoffice-kit 系列整体剔除 (office sidecar, Android 暂不支持)
set -euo pipefail

SRC="${1:?usage: pack-runtime.sh <deploy闭包目录> [assets输出目录]}"
DEST="${2:-$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/dsh-runtime}"
STAGE="$(mktemp -d)"

echo "[1/4] 复制闭包到临时目录 $STAGE"
cp -r "$SRC" "$STAGE/runtime"
cd "$STAGE/runtime"

echo "[2/4] 裁剪平台原生包与开发产物"
# 平台原生包（保留目录改名而非删除, 由 tar 打包时排除等价实现; 这里直接删除
# 因为 stage 是临时目录）
for d in node_modules/.pnpm/*win32-x64* node_modules/.pnpm/*win-x64* \
         node_modules/.pnpm/*darwin* node_modules/.pnpm/*linux-x64* \
         node_modules/.pnpm/*musl* node_modules/.pnpm/*libreoffice-kit-wasm*; do
  rm -rf $d 2>/dev/null || true
done
# workspace 包的开发产物
find . -path ./node_modules -prune -o -type d \( -name src -o -name tests \) -print 2>/dev/null | \
  grep -v "^./node_modules" | while read -r d; do rm -rf "$d"; done
find . -name "*.map" -delete 2>/dev/null || true
find . -name "*.d.ts" -path "*/lib/*" -delete 2>/dev/null || true

echo "[3/4] 生成版本标记"
VERSION=$(node -p "JSON.parse(require('fs').readFileSync('node_modules/@deepseek-ai/dsh/package.json')).version" 2>/dev/null || echo "unknown")
echo "$VERSION" > VERSION

echo "[4/4] 打包 tgz → $DEST"
mkdir -p "$DEST"
tar czf "$DEST/runtime.tgz" --owner=0 --group=0 .
cp VERSION "$DEST/VERSION"

SIZE=$(du -sh "$DEST/runtime.tgz" | cut -f1)
echo "完成: $DEST/runtime.tgz ($SIZE), dsh 版本 $VERSION"
echo "注意: APK assets 超过 100MB 需用 AAB 分发或改为启动后下载; 当前可先本地 sideload 调试"
