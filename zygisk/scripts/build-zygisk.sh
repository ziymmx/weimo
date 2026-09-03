#!/usr/bin/env bash
#
# Weimo (微末) Zygisk module builder.
#
# Compiles zygisk/jni/module.cpp for arm64-v8a / armeabi-v7a with the Android
# NDK, then assembles a Magisk / KernelSU module ZIP:
#
#   module.prop, customize.sh, config.sh, action.sh, webroot/, META-INF/
#   zygisk/<abi>.so            - native Zygisk module
#   payload/weimo.apk          - the Gradle release APK (Dex payload)
#   lib/<abi>/libpine.so       - Pine runtime, extracted from the APK
#   lib/<abi>/libdexkit.so     - DexKit runtime, extracted from the APK
#
# Usage:
#   bash zygisk/scripts/build-zygisk.sh [path-to-release.apk] [output-dir]
#
# Environment:
#   ANDROID_NDK_HOME / ANDROID_NDK_ROOT - NDK root (required when the NDK is
#                                         not discoverable under $ANDROID_HOME/ndk)
#   WEIMO_VERSION                        - version string; defaults to 1.0.0
#
# The module ZIP is written to <output-dir>/weimo-zygisk-<version>.zip.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

RELEASE_APK="${1:-app/build/outputs/apk/release/app-release.apk}"
OUT_DIR="${2:-dist}"
VERSION="${WEIMO_VERSION:-1.0.0}"

TEMPLATE_DIR="$REPO_ROOT/zygisk/template"
JNI_DIR="$REPO_ROOT/zygisk/jni"

if [ ! -f "$RELEASE_APK" ]; then
  echo "error: release APK not found: $RELEASE_APK" >&2
  exit 1
fi

# ---- Locate the NDK toolchain -------------------------------------------------
if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -n "${ANDROID_NDK_ROOT:-}" ]; then
  ANDROID_NDK_HOME="$ANDROID_NDK_ROOT"
fi
if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk" ]; then
  ANDROID_NDK_HOME="$(find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
fi
if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "$ANDROID_NDK_HOME" ]; then
  echo "error: Android NDK not found. Set ANDROID_NDK_HOME or install the NDK under \$ANDROID_HOME/ndk." >&2
  exit 1
fi

TOOLCHAIN_ROOT="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt"
case "$(uname -s)" in
  Linux*)  HOST_TAG="linux-x86_64" ;;
  Darwin*) HOST_TAG="darwin-x86_64" ;;
  MINGW*|MSYS*|CYGWIN*) HOST_TAG="windows-x86_64" ;;
  *) echo "error: unsupported host OS: $(uname -s)" >&2; exit 1 ;;
esac
CC_DIR="$TOOLCHAIN_ROOT/$HOST_TAG/bin"
if [ ! -d "$CC_DIR" ]; then
  echo "error: NDK toolchain not found: $CC_DIR" >&2
  exit 1
fi

COMMON_FLAGS=(-std=c++17 -shared -fPIC -O2 -s -Wall -Wextra -I"$JNI_DIR/include")

compile_for() {
  local abi="$1"
  local wrapper
  case "$abi" in
    arm64-v8a)   wrapper="aarch64-linux-android21-clang++" ;;
    armeabi-v7a) wrapper="armv7a-linux-androideabi21-clang++" ;;
    *) echo "error: unsupported ABI: $abi" >&2; exit 1 ;;
  esac

  local cc="$CC_DIR/$wrapper"
  local mode=direct
  if [ -x "$cc" ]; then
    :
  elif [ -f "$cc" ]; then
    # Extensionless launcher exists but is not executable: run it through bash
    # (this is the case for some Windows NDK layouts).
    mode=bash
  elif [ -f "$cc.cmd" ]; then
    # Windows NDK without the extensionless wrapper: drive the .cmd via cmd.exe.
    cc="cmd.exe //c \"$(cygpath -w "$cc.cmd")\""
    mode=cmd
  else
    echo "error: no compiler found for $abi in $CC_DIR" >&2
    exit 1
  fi

  echo ">> compiling $abi -> $STAGE/$abi.so (mode=$mode)"
  case "$mode" in
    direct)
      "$cc" "${COMMON_FLAGS[@]}" "$JNI_DIR/module.cpp" -o "$STAGE/$abi.so" -llog
      ;;
    bash)
      bash "$cc" "${COMMON_FLAGS[@]}" "$JNI_DIR/module.cpp" -o "$STAGE/$abi.so" -llog
      ;;
    cmd)
      # shellcheck disable=SC2086
      eval "$cc" "${COMMON_FLAGS[@]}" "$JNI_DIR/module.cpp" -o "$STAGE/$abi.so" -llog
      ;;
  esac
}

# ---- Stage the module tree ----------------------------------------------------
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

mkdir -p "$STAGE/module"
cp -R "$TEMPLATE_DIR/." "$STAGE/module/"
mkdir -p "$STAGE/module/zygisk" "$STAGE/module/payload"
mkdir -p "$STAGE/module/lib/arm64-v8a" "$STAGE/module/lib/armeabi-v7a"

compile_for arm64-v8a
compile_for armeabi-v7a
cp "$STAGE/arm64-v8a.so" "$STAGE/module/zygisk/arm64-v8a.so"
cp "$STAGE/armeabi-v7a.so" "$STAGE/module/zygisk/armeabi-v7a.so"

cp "$RELEASE_APK" "$STAGE/module/payload/weimo.apk"

echo ">> extracting native helper libraries from APK"
mkdir -p "$STAGE/apk"
for abi in arm64-v8a armeabi-v7a; do
  for lib in libpine.so libdexkit.so; do
    entry="lib/$abi/$lib"
    if ! unzip -l "$RELEASE_APK" "$entry" | grep -q " $entry$"; then
      echo "error: $RELEASE_APK does not contain $entry" >&2
      exit 1
    fi
    unzip -o -q "$RELEASE_APK" "$entry" -d "$STAGE/apk"
    cp "$STAGE/apk/$entry" "$STAGE/module/lib/$abi/$lib"
  done
done

# ---- Emit module.prop ---------------------------------------------------------
sed -i "s/^version=.*/version=$VERSION/" "$STAGE/module/module.prop"

# ---- Zip it -------------------------------------------------------------------
if [ "${OUT_DIR#/}" = "$OUT_DIR" ]; then
  OUT_DIR="$REPO_ROOT/$OUT_DIR"
fi
mkdir -p "$OUT_DIR"
ZIPFILE="$OUT_DIR/weimo-zygisk-$VERSION.zip"
rm -f "$ZIPFILE"
echo ">> packaging $ZIPFILE"
(cd "$STAGE/module" && zip -r -q "$ZIPFILE" .)
echo "built: $ZIPFILE"
