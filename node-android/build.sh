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
# node 26 V8 requires clang >= 19.1 on the host too (obj.host v8 regexp
# consteval lambdas fail with clang 18); the CI installs clang-19.
if command -v clang-19 >/dev/null 2>&1; then
  CC_host=clang-19
  CXX_host=clang++-19
else
  CC_host=clang
  CXX_host=clang++
fi
# gyp ninja generator reads LDFLAGS_host for the host toolset; V8's
# JSDispatchTable/wasm tables use 16-byte CAS that glibc only provides in
# libatomic, and node 26.13.1 has no configure option for host ldflags.
export LDFLAGS_host="-latomic"

# --- fetch source ---
SRC="$BUILD/node-v$NODE_VERSION"
if [ ! -f "$SRC/configure.py" ]; then
  curl -sL -o "$BUILD/node.tar.xz" "https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION.tar.xz"
  mkdir -p "$SRC"
  tar -xJf "$BUILD/node.tar.xz" -C "$SRC" --strip-components=1
fi

# --- apply android patches (from termux/termux-packages, MIT) ---
# NOTE: skip common.gypi.patch — termux removes the OS==android -I cpufeatures
# block because they use --shared-zlib; we compile bundled zlib and need that
# block, driven by the android_ndk_path gyp variable (see configure below).
cd "$SRC"
for p in "$ROOT"/patches/*.patch; do
  base=$(basename "$p")
  if [ "$base" = "common.gypi.patch" ]; then echo "skip $base (keep upstream cpufeatures include)"; continue; fi
  echo "applying $base"
  patch -p1 --forward < "$p" || echo "WARN: patch $base partially failed"
done

# --- vendored cpufeatures: node's bundled zlib expects cpu-features.h/.c at
#     <node-root>/sources/android/cpufeatures on android targets (termux ships
#     it as a package; we copy it from the NDK into the same relative path). ---
mkdir -p "$SRC/sources/android"
cp -r "$ANDROID_NDK_LATEST_HOME/sources/android/cpufeatures" "$SRC/sources/android/"

# --- build libcpufeatures.a: openssl/zlib's cpu detection CALLS
#     android_getCpuFeatures(); the implementation lives in NDK's
#     cpufeatures/cpu-features.c. node's configure.py filters the LDFLAGS env
#     for gyp, so instead we patch the generated target ninja files below. ---
"$CC" -c "$SRC/sources/android/cpufeatures/cpu-features.c" -o "$BUILD/cpu-features.o"
"$ANDROID_NDK_LATEST_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-ar" \
  rcs "$BUILD/libcpufeatures.a" "$BUILD/cpu-features.o"

# --- ccache 包装：CC/CXX 必须是单命令，gyp 展开时不接受带空格的命令串 ---
CCACHE_DIR="${CCACHE_DIR:-$HOME/.ccache}"
export CCACHE_DIR CCACHE_COMPILERCHECK=content CCACHE_MAXSIZE=10G CCACHE_SLOPPINESS=file_stat_matches,include_file_mtime
if command -v ccache >/dev/null 2>&1; then
  mkdir -p "$BUILD/ccwrap"
  for name in cc cxx hostcc hostcxx; do :; done
  printf '#!/bin/sh\nexec ccache %s "$@"\n' "$CC"  > "$BUILD/ccwrap/cc"
  printf '#!/bin/sh\nexec ccache %s "$@"\n' "$CXX" > "$BUILD/ccwrap/cxx"
  printf '#!/bin/sh\nexec ccache %s "$@"\n' "$CC_host"  > "$BUILD/ccwrap/hostcc"
  printf '#!/bin/sh\nexec ccache %s "$@"\n' "$CXX_host" > "$BUILD/ccwrap/hostcxx"
  chmod +x "$BUILD/ccwrap/"*
  CC="$BUILD/ccwrap/cc"
  CXX="$BUILD/ccwrap/cxx"
  CC_host="$BUILD/ccwrap/hostcc"
  CXX_host="$BUILD/ccwrap/hostcxx"
  echo "ccache enabled: $(ccache --version | head -1)"
  ccache --zero-stats >/dev/null 2>&1 || true
fi

# --- configure ---
export CC CXX CC_host CXX_host
export GYP_DEFINES="host_os=linux android_ndk_path=$ANDROID_NDK_LATEST_HOME"
CFLAGS="-O2 -fno-omit-frame-pointer"
LDFLAGS="-Wl,-z,max-page-size=16384"   # 16KB page alignment for Play compliance

python3 configure.py \
  --prefix=/data/data/dev.ads.shell/files/usr \
  --dest-cpu=arm64 \
  --dest-os=android \
  --cross-compiling \
  --without-npm \
  --with-intl=small-icu \
  --openssl-no-asm \
  --ninja \
  --verbose

# strip cross -I/-L that point at the (nonexistent) target prefix
for f in out/Release/obj.host/*.ninja; do
  [ -e "$f" ] && sed -i "s|-I/data/data/dev.ads.shell/files/usr/include||g; s|-L/data/data/dev.ads.shell/files/usr/lib||g" "$f"
done

# append libcpufeatures to every TARGET link step (gyp ninja rspfile order is
# libs -> in -> ldflags, so it lands after objects; host toolset untouched)
for f in out/Release/obj/*.ninja out/Release/obj/*/*.ninja; do
  [ -e "$f" ] && sed -i "s|^ldflags = |ldflags = -L$BUILD -lcpufeatures |" "$f"
done
grep -rl "lcpufeatures" out/Release/obj/ >/dev/null && echo "ninja patched: libcpufeatures linked"

# --- build ---
# dupbuild=warn: modern ninja treats duplicate stamp rules (v8_inspector_headers
# in cross builds) as errors; termux's nodejs recipe relies on the old warn-only
# behavior. See https://github.com/ninja-build/ninja/issues/1909
ninja -C out/Release -w dupbuild=warn -j "$(nproc)"

# --- ship as APKLF-named executable ---
STRIP="$TOOLCHAIN/bin/llvm-strip"
cp out/Release/node "$OUT/libnode.so"
"$STRIP" --strip-unneeded "$OUT/libnode.so"
"$TOOLCHAIN/bin/llvm-readelf" -h "$OUT/libnode.so" | head -5
ls -la "$OUT/libnode.so"
echo "OK: $OUT/libnode.so"
