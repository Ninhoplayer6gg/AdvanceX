// SPDX-License-Identifier: MPL-2.0
// The emulator core abstraction.
//
// AdvanceX talks to emulation exclusively through this interface. The
// production implementation wraps mGBA (see mgba_core.cpp), but nothing above
// this layer depends on mGBA headers, so the core can be upgraded or replaced
// without touching the runtime, the Advance Engine, or the Android frontend.
//
// Threading: an EmulatorCore instance is NOT thread-safe. The runtime owns it
// on a single emulation thread.
#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "ax/core/memory_bus.h"
#include "ax/core/types.h"

namespace ax::core {

struct LoadRequest {
    /// ROM image. The runtime may already have applied virtual (in-memory)
    /// patches to this buffer; the file on disk is never modified.
    std::vector<uint8_t> rom;
    /// Optional user-provided BIOS image (16 KiB). Empty = built-in HLE BIOS.
    std::vector<uint8_t> bios;
    /// Existing cartridge save data (may be empty for a new game).
    std::vector<uint8_t> saveData;
    /// Skip the BIOS boot animation when a real BIOS is supplied.
    bool skipBiosIntro = true;
};

class EmulatorCore {
public:
    virtual ~EmulatorCore() = default;

    /// Short identifier of the implementation, e.g. "mgba".
    virtual const char* id() const = 0;
    /// Version of the underlying implementation, e.g. "0.10.5".
    virtual const char* version() const = 0;

    virtual bool load(LoadRequest request, std::string* error) = 0;
    virtual void unload() = 0;
    virtual bool isLoaded() const = 0;
    virtual void reset() = 0;

    /// Emulates exactly one video frame.
    virtual void runFrame() = 0;

    /// Sets the full key state (bitmask of ax::core::Key).
    virtual void setKeys(uint32_t keys) = 0;

    /// Most recent completed frame. Valid until the next runFrame().
    virtual FrameView frame() const = 0;

    /// Native refresh rate of the emulated machine (~59.7275 Hz for GBA).
    virtual double framesPerSecond() const = 0;
    virtual uint32_t frameCounter() const = 0;

    /// Skip rendering of N out of every N+1 frames (0 = render all). Emulation
    /// itself is never skipped, only the software rasterizer output.
    virtual void setFrameSkip(int frames) = 0;

    // --- Audio -----------------------------------------------------------
    /// Output sample rate the core should resample to. Can be nudged slightly
    /// at runtime for dynamic rate control.
    virtual void setAudioSampleRate(double hz) = 0;
    /// Moves up to `maxFrames` stereo frames (interleaved L/R int16) out of the
    /// core. Returns the number of frames written.
    virtual size_t drainAudio(int16_t* interleaved, size_t maxFrames) = 0;

    // --- Save states -----------------------------------------------------
    virtual size_t stateSize() = 0;
    virtual bool saveState(std::vector<uint8_t>* out) = 0;
    virtual bool loadState(const uint8_t* data, size_t size) = 0;

    // --- Cartridge saves (SRAM / Flash / EEPROM) ---------------------------
    /// True once the game has finished writing new save data since the last
    /// call (the flag is cleared by this call).
    virtual bool consumeSaveDirty() = 0;
    /// Snapshot of the current cartridge save memory (empty if none).
    virtual std::vector<uint8_t> saveData() = 0;

    // --- Introspection ----------------------------------------------------
    virtual CartridgeHeader header() const = 0;
    virtual MemoryBus& memory() = 0;
};

/// Factory for the mGBA-backed implementation.
std::unique_ptr<EmulatorCore> createMgbaCore();

/// Parses a GBA cartridge header from a raw ROM image (no core needed).
bool parseCartridgeHeader(const uint8_t* rom, size_t size, CartridgeHeader* out);

}  // namespace ax::core
