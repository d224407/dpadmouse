#!/bin/sh
# Builds native/evgrab.c for every ABI into app/src/main/assets/evgrab/<abi> (needs the Android NDK).
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}}"
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
OUT="$ROOT/app/src/main/assets/evgrab"
mkdir -p "$OUT"
build() { "$BIN/$2-clang" -O2 -s -o "$OUT/$1" "$ROOT/native/evgrab.c"; }
build arm64-v8a aarch64-linux-android26
build armeabi-v7a armv7a-linux-androideabi26
build x86_64 x86_64-linux-android26
build x86 i686-linux-android26
