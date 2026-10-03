// SPDX-License-Identifier: MPL-2.0
#include "ax/engine/sprite_detector.h"

#include <cstdio>

#include "ax/common/hash.h"

namespace ax::engine {
namespace {

// OBJ sizes indexed by [shape][size]: {width, height}.
constexpr int kObjSizes[3][4][2] = {
    {{8, 8}, {16, 16}, {32, 32}, {64, 64}},  // square
    {{16, 8}, {32, 8}, {32, 16}, {64, 32}},  // horizontal
    {{8, 16}, {8, 32}, {16, 32}, {32, 64}},  // vertical
};

constexpr uint32_t kDispcntAddress = 0x04000000;
constexpr uint32_t kObjVramOffset = 0x10000;  // OBJ tiles start at 0x06010000

struct SpriteGeometry {
    int tilesWide;
    int tilesHigh;
    bool oneDimensional;
};

/// Byte offset (inside VRAM) of the tile at (tx, ty) of a sprite. Tile
/// indices are in 32-byte units; an 8bpp tile spans two of them.
uint32_t tileOffset(const SpriteInstance& s, const SpriteGeometry& g, int tx, int ty) {
    uint32_t step = s.is8bpp ? 2 : 1;
    uint32_t index;
    if (g.oneDimensional) {
        index = s.tileIndex + static_cast<uint32_t>(ty * g.tilesWide + tx) * step;
    } else {
        index = s.tileIndex + static_cast<uint32_t>(ty * 32) + static_cast<uint32_t>(tx) * step;
    }
    return kObjVramOffset + (index & 0x3FF) * 32;
}

}  // namespace

std::string hashToHex(uint64_t hash) {
    char buf[17];
    std::snprintf(buf, sizeof(buf), "%016llx", static_cast<unsigned long long>(hash));
    return buf;
}

bool hexToHash(const std::string& text, uint64_t* out) {
    if (text.size() != 16) return false;
    uint64_t v = 0;
    for (char c : text) {
        v <<= 4;
        if (c >= '0' && c <= '9') v |= static_cast<uint64_t>(c - '0');
        else if (c >= 'a' && c <= 'f') v |= static_cast<uint64_t>(c - 'a' + 10);
        else if (c >= 'A' && c <= 'F') v |= static_cast<uint64_t>(c - 'A' + 10);
        else return false;
    }
    *out = v;
    return true;
}

void SpriteDetector::scan(core::MemoryBus& bus, std::vector<SpriteInstance>* out) const {
    std::vector<SpriteInstance>& result = *out;
    result.clear();
    size_t oamSize = 0, vramSize = 0, palSize = 0;
    const uint8_t* oam = bus.regionData(core::MemoryRegion::Oam, &oamSize);
    const uint8_t* vram = bus.regionData(core::MemoryRegion::Vram, &vramSize);
    const uint8_t* pal = bus.regionData(core::MemoryRegion::Palette, &palSize);
    if (!oam || !vram || !pal || oamSize < 1024 || vramSize < 0x18000 || palSize < 1024) return;

    uint16_t dispcnt = bus.read16(kDispcntAddress);
    if (!(dispcnt & 0x1000)) return;  // OBJ layer disabled
    bool oneDimensional = dispcnt & 0x0040;

    for (int i = 0; i < 128; ++i) {
        const uint8_t* e = oam + i * 8;
        uint16_t a0 = static_cast<uint16_t>(e[0] | (e[1] << 8));
        uint16_t a1 = static_cast<uint16_t>(e[2] | (e[3] << 8));
        uint16_t a2 = static_cast<uint16_t>(e[4] | (e[5] << 8));
        int objMode = (a0 >> 8) & 3;
        if (objMode == 2) continue;          // hidden
        if (((a0 >> 10) & 3) == 2) continue;  // OBJ window, not drawn
        int shape = (a0 >> 14) & 3;
        if (shape == 3) continue;  // prohibited
        int size = (a1 >> 14) & 3;

        SpriteInstance s;
        s.oamIndex = i;
        s.width = kObjSizes[shape][size][0];
        s.height = kObjSizes[shape][size][1];
        s.affine = objMode & 1;
        s.is8bpp = a0 & 0x2000;
        s.x = a1 & 0x1FF;
        if (s.x >= 240) s.x -= 512;
        s.y = a0 & 0xFF;
        if (s.y >= 160) s.y -= 256;
        if (!s.affine) {
            s.hflip = a1 & 0x1000;
            s.vflip = a1 & 0x2000;
        }
        s.tileIndex = a2 & 0x3FF;
        s.priority = (a2 >> 10) & 3;
        s.paletteBank = (a2 >> 12) & 0xF;
        int boundW = (objMode == 3) ? s.width * 2 : s.width;
        int boundH = (objMode == 3) ? s.height * 2 : s.height;
        if (s.x + boundW <= 0 || s.x >= 240 || s.y + boundH <= 0 || s.y >= 160) continue;  // off-screen

        SpriteGeometry g{s.width / 8, s.height / 8, oneDimensional};
        uint64_t h = ax::hash::kFnvOffset;
        uint32_t dims = static_cast<uint32_t>(s.width | (s.height << 8) | (s.is8bpp ? 0x10000 : 0));
        h = ax::hash::fnv1a64(&dims, sizeof(dims), h);
        const uint32_t tileBytes = s.is8bpp ? 64 : 32;
        for (int ty = 0; ty < g.tilesHigh; ++ty) {
            for (int tx = 0; tx < g.tilesWide; ++tx) {
                uint32_t off = tileOffset(s, g, tx, ty);
                if (off + tileBytes > vramSize) continue;
                h = ax::hash::fnv1a64(vram + off, tileBytes, h);
            }
        }
        s.tileHash = h;
        const uint8_t* objPal = pal + 0x200;
        s.paletteHash = s.is8bpp ? ax::hash::fnv1a64(objPal, 512) : ax::hash::fnv1a64(objPal + s.paletteBank * 32, 32);
        result.push_back(s);
    }
}

bool SpriteDetector::decode(core::MemoryBus& bus, const SpriteInstance& s, std::vector<uint32_t>* rgba) const {
    size_t vramSize = 0, palSize = 0;
    const uint8_t* vram = bus.regionData(core::MemoryRegion::Vram, &vramSize);
    const uint8_t* pal = bus.regionData(core::MemoryRegion::Palette, &palSize);
    if (!vram || !pal || s.width <= 0 || s.height <= 0) return false;
    bool oneDimensional = bus.read16(kDispcntAddress) & 0x0040;
    SpriteGeometry g{s.width / 8, s.height / 8, oneDimensional};
    const uint8_t* objPal = pal + 0x200;
    rgba->assign(static_cast<size_t>(s.width) * s.height, 0);
    auto color = [&](unsigned index) -> uint32_t {
        uint16_t c = static_cast<uint16_t>(objPal[index * 2] | (objPal[index * 2 + 1] << 8));
        uint32_t r = c & 31, gg = (c >> 5) & 31, b = (c >> 10) & 31;
        r = (r << 3) | (r >> 2);
        gg = (gg << 3) | (gg >> 2);
        b = (b << 3) | (b >> 2);
        return 0xFF000000u | (b << 16) | (gg << 8) | r;
    };
    for (int ty = 0; ty < g.tilesHigh; ++ty) {
        for (int tx = 0; tx < g.tilesWide; ++tx) {
            uint32_t off = tileOffset(s, g, tx, ty);
            for (int py = 0; py < 8; ++py) {
                for (int px = 0; px < 8; ++px) {
                    unsigned index;
                    if (s.is8bpp) {
                        uint32_t at = off + static_cast<uint32_t>(py * 8 + px);
                        if (at >= vramSize) return false;
                        index = vram[at];
                    } else {
                        uint32_t at = off + static_cast<uint32_t>(py * 4 + px / 2);
                        if (at >= vramSize) return false;
                        index = (px & 1) ? (vram[at] >> 4) : (vram[at] & 0xF);
                        if (index) index += static_cast<unsigned>(s.paletteBank) * 16;
                    }
                    if (!index) continue;
                    (*rgba)[static_cast<size_t>(ty * 8 + py) * s.width + tx * 8 + px] = color(index);
                }
            }
        }
    }
    return true;
}

}  // namespace ax::engine
