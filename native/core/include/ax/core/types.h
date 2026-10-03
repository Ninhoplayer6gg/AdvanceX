// SPDX-License-Identifier: MPL-2.0
// Basic GBA-level types shared by the core interface and its users.
#pragma once

#include <cstdint>
#include <string>

namespace ax::core {

constexpr int kGbaWidth = 240;
constexpr int kGbaHeight = 160;

/// GBA key bits, in the same order as the hardware KEYINPUT register.
enum Key : uint32_t {
    kKeyA = 1u << 0,
    kKeyB = 1u << 1,
    kKeySelect = 1u << 2,
    kKeyStart = 1u << 3,
    kKeyRight = 1u << 4,
    kKeyLeft = 1u << 5,
    kKeyUp = 1u << 6,
    kKeyDown = 1u << 7,
    kKeyR = 1u << 8,
    kKeyL = 1u << 9,
    kKeyMask = 0x3FFu,
};

/// Fields read from the cartridge header (offsets 0xA0..0xBD).
struct CartridgeHeader {
    std::string title;      // up to 12 ASCII characters
    std::string gameCode;   // 4 characters, e.g. "AXVE"
    std::string makerCode;  // 2 characters
    uint8_t version = 0;    // software revision (0xBC)
    uint8_t checksum = 0;   // header complement check (0xBD)
    bool checksumValid = false;
};

/// A read-only view of the most recent video frame.
/// Pixels are 32-bit, byte order R, G, B, X (GL_RGBA / GL_UNSIGNED_BYTE).
struct FrameView {
    const uint32_t* pixels = nullptr;
    int width = 0;
    int height = 0;
    int stridePixels = 0;
};

}  // namespace ax::core
