#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Generates the original demo artwork used by the bundled test-cartridge
profile and the example .advx mod (high-resolution sprite replacements).

Everything is drawn procedurally here (no third-party art), with 4x4
supersampled anti-aliasing. Output: RGBA PNG files.

Usage: make_demo_art.py <repo-root>
"""
import math
import os
import struct
import sys
import zlib


def write_png(path, width, height, rgba_rows):
    raw = b"".join(b"\x00" + bytes(row) for row in rgba_rows)

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)


def render(size, shader, samples=4):
    """shader(u, v) -> (r, g, b, a) in 0..1 for u, v in [-1, 1]."""
    rows = []
    for y in range(size):
        row = []
        for x in range(size):
            acc = [0.0, 0.0, 0.0, 0.0]
            for sy in range(samples):
                for sx in range(samples):
                    u = ((x + (sx + 0.5) / samples) / size) * 2 - 1
                    v = ((y + (sy + 0.5) / samples) / size) * 2 - 1
                    r, g, b, a = shader(u, v)
                    acc[0] += r * a
                    acc[1] += g * a
                    acc[2] += b * a
                    acc[3] += a
            n = samples * samples
            a = acc[3] / n
            if a > 0:
                r, g, b = (acc[i] / acc[3] for i in range(3))
            else:
                r = g = b = 0
            row += [int(round(max(0, min(1, c)) * 255)) for c in (r, g, b, a)]
        rows.append(row)
    return rows


def mix(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def crystal(palette):
    dark, mid, light, core = palette

    def shader(u, v):
        d = abs(u) + abs(v)  # diamond distance
        if d > 0.97:
            return (0, 0, 0, 0)
        if d > 0.86:
            return dark + (1.0,)
        # Four facets lit from the top-left.
        facet = (-u - v) * 0.5 + 0.5
        base = mix(mid, light, max(0.0, min(1.0, facet)))
        # Bright core with a soft falloff.
        glow = max(0.0, 1.0 - d / 0.55)
        color = mix(base, core, glow ** 1.5)
        # Specular streak.
        streak = math.exp(-((u + v + 0.45) ** 2) / 0.004) * (1 - d)
        color = mix(color, (1.0, 1.0, 1.0), min(1.0, streak * 1.4))
        return color + (1.0,)

    return shader


def star(u, v):
    angle = math.atan2(v, u) + math.pi / 2
    r = math.hypot(u, v)
    spikes = 5
    k = math.cos(spikes * angle) * 0.5 + 0.5
    radius = 0.42 + 0.5 * k ** 3
    if r > radius:
        return (0, 0, 0, 0)
    edge = r / radius
    gold_dark = (0.55, 0.30, 0.02)
    gold = (1.0, 0.78, 0.18)
    white = (1.0, 0.98, 0.85)
    color = mix(gold, gold_dark, edge ** 2)
    color = mix(color, white, max(0.0, 1 - r / 0.25) ** 2)
    if edge > 0.9:
        color = mix(color, gold_dark, 0.7)
    return color + (1.0,)


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    violet = ((0.20, 0.06, 0.45), (0.45, 0.20, 0.90), (0.30, 0.72, 1.00), (0.88, 1.00, 1.00))
    gold = ((0.40, 0.18, 0.00), (0.78, 0.45, 0.06), (1.00, 0.78, 0.20), (1.00, 1.00, 0.70))
    profile = os.path.join(root, "assets/advance/profiles/advancex_testcart/replacements")
    write_png(os.path.join(profile, "crystal_violet_hd.png"), 64, 64, render(64, crystal(violet)))
    write_png(os.path.join(profile, "crystal_gold_hd.png"), 64, 64, render(64, crystal(gold)))
    mod = os.path.join(root, "mods/examples/advancex-demo-pack/sprites")
    write_png(os.path.join(mod, "star_hd.png"), 64, 64, render(64, star))
    print("demo art written")


if __name__ == "__main__":
    main()
