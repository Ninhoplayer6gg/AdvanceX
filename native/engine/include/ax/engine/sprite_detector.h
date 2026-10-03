// SPDX-License-Identifier: MPL-2.0
// Sprite (OBJ) detection for the asset-replacement system. EXPERIMENTAL.
//
//   original asset ──► detection (this file) ──► replacement database
//                                               └► custom high-res asset
//
// After each frame the detector reads OAM, OBJ VRAM and the OBJ palette
// through the side-effect-free MemoryBus and fingerprints every visible
// sprite. Fingerprints are stable across sessions and devices, so a
// replacement pack can map them to high-resolution artwork.
//
// Current limitations (documented in docs/MODDING.md): affine sprites are
// reported but not replaced, and overlays are composited above all layers.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "ax/core/memory_bus.h"

namespace ax::engine {

struct SpriteInstance {
    int oamIndex = 0;
    uint64_t tileHash = 0;     // pixel indices only (palette independent)
    uint64_t paletteHash = 0;  // the palette the sprite is drawn with
    int x = 0;                 // screen position (may be negative / partially off-screen)
    int y = 0;
    int width = 0;
    int height = 0;
    bool hflip = false;
    bool vflip = false;
    bool affine = false;
    bool is8bpp = false;
    int priority = 0;
    int paletteBank = 0;
    uint32_t tileIndex = 0;
};

/// Combined key used by replacement packs: tile data + palette.
inline uint64_t replacementKey(const SpriteInstance& s) {
    return s.tileHash ^ (s.paletteHash * 0x9E3779B97F4A7C15ull);
}

std::string hashToHex(uint64_t hash);
bool hexToHash(const std::string& text, uint64_t* out);

class SpriteDetector {
public:
    /// Scans all 128 OAM entries and stores the visible sprites in `out`
    /// (cleared first; capacity is reused to avoid per-frame allocation).
    void scan(core::MemoryBus& bus, std::vector<SpriteInstance>* out) const;

    /// Decodes a sprite to RGBA8888 (R in the low byte, index 0 transparent)
    /// in its unflipped orientation. Used for dumping assets.
    bool decode(core::MemoryBus& bus, const SpriteInstance& sprite, std::vector<uint32_t>* rgba) const;
};

}  // namespace ax::engine
