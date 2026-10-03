// SPDX-License-Identifier: MPL-2.0
// Helpers shared by native tests.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "ax/common/file_io.h"
#include "ax/core/emulator_core.h"
#include "test_framework.h"

namespace axtest {

inline std::vector<uint8_t> loadTestRom() {
    std::vector<uint8_t> rom;
    std::string path = assetsDir() + "/advancex-testcart.gba";
    if (!ax::fileio::readFile(path, &rom) || rom.empty()) {
        throw Failure{"cannot read test ROM at " + path + " (pass --assets <dir>)"};
    }
    return rom;
}

inline std::unique_ptr<ax::core::EmulatorCore> bootTestRom(std::vector<uint8_t> save = {}) {
    auto core = ax::core::createMgbaCore();
    ax::core::LoadRequest request;
    request.rom = loadTestRom();
    request.saveData = std::move(save);
    std::string error;
    if (!core->load(std::move(request), &error)) {
        throw Failure{"core->load failed: " + error};
    }
    return core;
}

inline void runFrames(ax::core::EmulatorCore& core, int frames, uint32_t keys = 0) {
    core.setKeys(keys);
    for (int i = 0; i < frames; ++i) {
        core.runFrame();
    }
}

inline uint64_t frameHash(const ax::core::EmulatorCore& core) {
    auto frame = core.frame();
    uint64_t h = 1469598103934665603ull;
    for (int y = 0; y < frame.height; ++y) {
        for (int x = 0; x < frame.width; ++x) {
            uint32_t p = frame.pixels[y * frame.stridePixels + x] & 0x00FFFFFFu;
            h = (h ^ p) * 1099511628211ull;
        }
    }
    return h;
}

/// Counts pixels of an exact RGB colour (0x00BBGGRR) and reports their centroid.
inline int findColor(const ax::core::EmulatorCore& core, uint32_t rgb, double* cx = nullptr, double* cy = nullptr) {
    auto frame = core.frame();
    long sx = 0, sy = 0;
    int count = 0;
    for (int y = 0; y < frame.height; ++y) {
        for (int x = 0; x < frame.width; ++x) {
            if ((frame.pixels[y * frame.stridePixels + x] & 0x00FFFFFFu) == rgb) {
                sx += x;
                sy += y;
                ++count;
            }
        }
    }
    if (count) {
        if (cx) *cx = static_cast<double>(sx) / count;
        if (cy) *cy = static_cast<double>(sy) / count;
    }
    return count;
}

/// RGB555 -> the 32-bit layout mGBA produces (R in the low byte, 5-bit
/// channels expanded to 8 bits by bit replication).
constexpr uint32_t expand5(int c) { return static_cast<uint32_t>((c << 3) | (c >> 2)); }
constexpr uint32_t rgb15ToFrame(int r, int g, int b) { return expand5(r) | (expand5(g) << 8) | (expand5(b) << 16); }

}  // namespace axtest
