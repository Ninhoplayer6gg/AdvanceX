// SPDX-License-Identifier: MPL-2.0
#include "ax/core/emulator_core.h"
#include "ax/core/memory_bus.h"

#include <cstring>

namespace ax::core {
namespace {

const MemoryRegionInfo kRegions[] = {
    {MemoryRegion::Bios, 0x00000000u, 0x00004000u, "BIOS"},
    {MemoryRegion::Ewram, 0x02000000u, 0x00040000u, "EWRAM"},
    {MemoryRegion::Iwram, 0x03000000u, 0x00008000u, "IWRAM"},
    {MemoryRegion::Io, 0x04000000u, 0x00000400u, "IO"},
    {MemoryRegion::Palette, 0x05000000u, 0x00000400u, "Palette"},
    {MemoryRegion::Vram, 0x06000000u, 0x00018000u, "VRAM"},
    {MemoryRegion::Oam, 0x07000000u, 0x00000400u, "OAM"},
    {MemoryRegion::Rom, 0x08000000u, 0x02000000u, "ROM"},
    {MemoryRegion::Save, 0x0E000000u, 0x00010000u, "Save"},
};

const MemoryRegionInfo kInvalid{};

}  // namespace

const MemoryRegionInfo& regionInfo(MemoryRegion region) {
    int index = static_cast<int>(region);
    if (index < 0 || index >= static_cast<int>(MemoryRegion::Count)) {
        return kInvalid;
    }
    return kRegions[index];
}

MemoryRegion classifyAddress(uint32_t address) {
    for (const auto& info : kRegions) {
        if (address >= info.base && address - info.base < info.size) {
            return info.region;
        }
    }
    return MemoryRegion::Invalid;
}

bool parseCartridgeHeader(const uint8_t* rom, size_t size, CartridgeHeader* out) {
    if (!rom || size < 0xC0 || !out) {
        return false;
    }
    auto readAscii = [&](size_t offset, size_t length) {
        std::string s;
        for (size_t i = 0; i < length; ++i) {
            char c = static_cast<char>(rom[offset + i]);
            if (c == '\0') {
                break;
            }
            s.push_back((c >= 0x20 && c < 0x7F) ? c : '?');
        }
        // Trim trailing spaces some dumps pad with.
        while (!s.empty() && s.back() == ' ') {
            s.pop_back();
        }
        return s;
    };
    out->title = readAscii(0xA0, 12);
    out->gameCode = readAscii(0xAC, 4);
    out->makerCode = readAscii(0xB0, 2);
    out->version = rom[0xBC];
    out->checksum = rom[0xBD];
    // Header complement check: -(sum of 0xA0..0xBC) - 0x19.
    uint8_t sum = 0;
    for (size_t i = 0xA0; i <= 0xBC; ++i) {
        sum = static_cast<uint8_t>(sum - rom[i]);
    }
    sum = static_cast<uint8_t>(sum - 0x19);
    out->checksumValid = sum == out->checksum;
    return true;
}

}  // namespace ax::core
