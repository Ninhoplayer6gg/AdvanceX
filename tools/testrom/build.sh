#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Builds the AdvanceX test cartridge (dist/advancex-testcart.gba).
#
# Requirements: clang with the ARM backend, ld.lld, llvm-objcopy, python3.
# The Android NDK toolchain works (it is preferred when ANDROID_NDK_HOME is set).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/build"
DIST="$HERE/dist"
mkdir -p "$OUT" "$DIST"

find_tool() {
    local name="$1"
    if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
        local ndk_bin="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/$name"
        if [[ -x "$ndk_bin" ]]; then echo "$ndk_bin"; return; fi
    fi
    command -v "$name" || { echo "error: '$name' not found" >&2; exit 1; }
}

CC="${CC_ARM:-$(find_tool clang)}"
LD="$(find_tool ld.lld)"
OBJCOPY="$(find_tool llvm-objcopy)"

CFLAGS=(--target=armv4t-none-eabi -mcpu=arm7tdmi -mthumb -O2 -std=c11
        -ffreestanding -fno-builtin -nostdlib -fno-common -ffunction-sections -fdata-sections
        -Wall -Wextra -Werror)

"$CC" "${CFLAGS[@]}" -c "$HERE/src/main.c" -o "$OUT/main.o"
"$CC" "${CFLAGS[@]}" -c "$HERE/src/font.c" -o "$OUT/font.o"
"$CC" --target=armv4t-none-eabi -mcpu=arm7tdmi -marm -c "$HERE/src/crt0.s" -o "$OUT/crt0.o"
"$LD" -T "$HERE/link.ld" --gc-sections -o "$OUT/testcart.elf" "$OUT/crt0.o" "$OUT/main.o" "$OUT/font.o"
"$OBJCOPY" -O binary "$OUT/testcart.elf" "$DIST/advancex-testcart.gba"
python3 "$HERE/fix_header.py" "$DIST/advancex-testcart.gba"
sha1sum "$DIST/advancex-testcart.gba" | tee "$DIST/advancex-testcart.sha1"
