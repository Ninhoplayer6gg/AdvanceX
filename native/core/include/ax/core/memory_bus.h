// SPDX-License-Identifier: MPL-2.0
// Side-effect-free access to emulated memory.
//
// This is the *only* way the Advance Engine touches the emulated machine.
// Reads and writes here never trigger I/O register side effects, DMA, or
// timing changes, which keeps runtime patches and asset detection from
// perturbing emulation in unexpected ways.
#pragma once

#include <cstddef>
#include <cstdint>

namespace ax::core {

enum class MemoryRegion : int {
    Bios = 0,
    Ewram,    // 0x02000000, 256 KiB on-board work RAM
    Iwram,    // 0x03000000, 32 KiB in-chip work RAM
    Io,       // 0x04000000, I/O registers (read-only for the engine)
    Palette,  // 0x05000000, 1 KiB
    Vram,     // 0x06000000, 96 KiB
    Oam,      // 0x07000000, 1 KiB
    Rom,      // 0x08000000, up to 32 MiB
    Save,     // 0x0E000000, cartridge SRAM/Flash window
    Count,
    Invalid = -1,
};

struct MemoryRegionInfo {
    MemoryRegion region = MemoryRegion::Invalid;
    uint32_t base = 0;
    uint32_t size = 0;
    const char* name = "";
};

/// Static GBA memory map (independent of any core implementation).
const MemoryRegionInfo& regionInfo(MemoryRegion region);

/// Classifies an address. Returns Invalid for unmapped space.
MemoryRegion classifyAddress(uint32_t address);

class MemoryBus {
public:
    virtual ~MemoryBus() = default;

    virtual uint8_t read8(uint32_t address) = 0;
    virtual uint16_t read16(uint32_t address) = 0;
    virtual uint32_t read32(uint32_t address) = 0;

    virtual void write8(uint32_t address, uint8_t value) = 0;
    virtual void write16(uint32_t address, uint16_t value) = 0;
    virtual void write32(uint32_t address, uint32_t value) = 0;

    /// Direct pointer to a whole region for fast bulk reads (VRAM/OAM/palette
    /// scanning). Returns nullptr if the region is unavailable. The pointer is
    /// valid until the next game load/unload and must only be used on the
    /// emulation thread.
    virtual const uint8_t* regionData(MemoryRegion region, size_t* size) = 0;

    /// Size of the loaded ROM image in bytes.
    virtual uint32_t romSize() const = 0;
};

}  // namespace ax::core
