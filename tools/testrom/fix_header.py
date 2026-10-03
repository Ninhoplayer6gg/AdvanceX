#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Finalises a raw GBA ROM image produced by the test-cartridge build.

* computes the header complement checksum at 0xBD
* pads the image to a multiple of 4 bytes

Usage: fix_header.py <rom.gba>
"""
import sys


def header_checksum(rom: bytes) -> int:
    total = 0
    for byte in rom[0xA0:0xBD]:
        total = (total - byte) & 0xFF
    return (total - 0x19) & 0xFF


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    path = sys.argv[1]
    data = bytearray(open(path, "rb").read())
    if len(data) < 0xC0:
        print(f"{path}: image too small ({len(data)} bytes)", file=sys.stderr)
        return 1
    data[0xBD] = header_checksum(data)
    while len(data) % 4:
        data.append(0)
    with open(path, "wb") as out:
        out.write(data)
    print(f"{path}: {len(data)} bytes, header checksum 0x{data[0xBD]:02X}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
