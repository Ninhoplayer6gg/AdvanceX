#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Verifies that the JNI method table in native/jni/advancex_jni.cpp matches
the `external fun` declarations of NativeBridge exactly (names and type
descriptors). A mismatch would make RegisterNatives fail at app start, so
this runs in CI instead of waiting for a device.

Usage: check-jni.py <compiled classes dir or jar> [repo root]
Requires `javap` (JDK).
"""
import os
import re
import subprocess
import sys

CLASS = "io.advancex.app.bridge.NativeBridge"


def kotlin_natives(classpath):
    out = subprocess.run(["javap", "-s", "-p", "-cp", classpath, CLASS], capture_output=True, text=True, check=True).stdout
    natives = {}
    lines = out.splitlines()
    for i, line in enumerate(lines):
        if " native " in line and "(" in line:
            name = re.search(r"\s(\w+)\(", line).group(1)
            desc = lines[i + 1].strip().removeprefix("descriptor:").strip()
            natives[name] = desc
    return natives


def cpp_table(root):
    src = open(os.path.join(root, "native/jni/advancex_jni.cpp")).read()
    table = {}
    for m in re.finditer(r'\{"(\w+)",\s*"([^"]+)"', src):
        table[m.group(1)] = m.group(2)
    return table


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    classpath = sys.argv[1]
    root = sys.argv[2] if len(sys.argv) > 2 else os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    kotlin = kotlin_natives(classpath)
    cpp = cpp_table(root)
    errors = []
    for name, desc in sorted(kotlin.items()):
        if name not in cpp:
            errors.append(f"missing in C++ table: {name}{desc}")
        elif cpp[name] != desc:
            errors.append(f"descriptor mismatch for {name}: Kotlin {desc} vs C++ {cpp[name]}")
    for name in sorted(set(cpp) - set(kotlin)):
        errors.append(f"C++ registers {name} which NativeBridge does not declare")
    if errors:
        print("\n".join(errors))
        return 1
    print(f"JNI bindings OK: {len(kotlin)} native methods match")
    return 0


if __name__ == "__main__":
    sys.exit(main())
