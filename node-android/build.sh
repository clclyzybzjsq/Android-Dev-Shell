#!/usr/bin/env bash
# Cross-compile Node.js for Android arm64 (bionic) -> libnode.so
#
# Based on the Termux nodejs packaging recipe (MIT, termux/termux-packages).
# Differences from Termux: bundled deps (openssl/zlib/cares/sqlite/small-icu)
# so the output binary is self-contained — no termux prefix, no shared libs.
#
# Inputs (env):
#   NODE_VERSION   e.g. 26.13.1
#   ANDROID_NDK_LATEST_HOME  NDK root (r27+/r28), contains toolchains/llvm
# Output: node-android/out/libnode.so (PIE arm64 executable, APKLF-named)
set -euo pipefail

NODE_VERSION="${NODE_VERSION:?set NODE_VERSION}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
BUILD="$ROOT/build"
OUT="$ROOT/out"
ARCH=arm64-v8a
HOST_TAG=linux-x86_64

mkdir -p "$BUILD" "$OUT"

# --- toolchain ---
TOOLCHAIN="$ANDROID_NDK_LATEST_HOME/toolchains/llvm/prebuilt/$HOST_TAG"
API=28
CC="$TOOLCHAIN/bin/aarch64-linux-android$API-clang"
CXX="$TOOLCHAIN/bin/aarch64-linux-android$API-clang++"
[ -x "$CC" ] || { echo "NDK clang not found at $CC"; exit 1; }

# --- host compiler for node_js2c / v8 codegen ---
CC_host=clang
CXX_host=clang++

# --- fetch source ---
SRC="$BUILD/node-v$NODE_VERSION"
if [ ! -f "$SRC/configure.py" ]; then
  curl -sL -o "$BUILD/node.tar.xz" "https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION.tar.xz"
  mkdir -p "$SRC"
  tar -xJf "$BUILD/node.tar.xz" -C "$SRC" --strip-components=1
fi

# --- apply android patches (from termux/termux-packages, MIT) ---
cd "$SRC"
for p in "$ROOT"/patches/*.patch; do
  echo "applying $(basename "$p")"
  patch -p1 --forward < "$p" || echo "WARN: patch $(basename "$p") partially failed"
done

# --- configure ---
export CC CXX CC_host CXX_host LINK_host="$CXX_host"
export GYP_DEFINES="host_os=linux"
CFLAGS="-O2 -fno-omit-frame-pointer"
LDFLAGS="-Wl,-z,max-page-size=16384"   # 16KB page alignment for Play compliance

python3 configure.py \
  --prefix=/data/data/dev.ads.shell/files/usr \
  --dest-cpu=arm64 \
  --dest-os=android \
  --cross-compiling \
  --without-npm \
  --with-intl=small-icu \
  --ninja \
  --verbose

# strip cross -I/-L that point at the (nonexistent) target prefix
for f in out/Release/obj.host/*.ninja; do
  [ -e "$f" ] && sed -i "s|-I/data/data/dev.ads.shell/files/usr/include||g; s|-L/data/data/dev.ads.shell/files/usr/lib||g" "$f"
done

# --- build ---
ninja -C out/Release -j "$(nproc)"

# --- ship as APKLF-named executable ---
STRIP="$TOOLCHAIN/bin/llvm-strip"
cp out/Release/node "$OUT/libnode.so"
"$STRIP" --strip-unneeded "$OUT/libnode.so"
"$TOOLCHAIN/bin/llvm-readelf" -h "$OUT/libnode.so" | head -5
ls -la "$OUT/libnode.so"
echo "OK: $OUT/libnode.so"
