// SPDX-License-Identifier: MPL-2.0
// Runtime (virtual) patches.
//
//   ROM file on disk ──(read)──► in-memory ROM copy ──(RomPatch)──► core
//                                     emulated RAM ◄──(MemoryPatch, per frame)
//
// The .gba file is never modified. Every patch is validated before use:
//  - addresses must fall inside an allowed, writable region
//  - optional `expect` bytes must match what is currently there, which pins
//    a patch to the exact ROM revision it was written for
//  - a failing patch is disabled on its own; the game keeps running
//
// Patch definitions are parsed and verified against the ROM hash by the
// Kotlin Advance Engine first; this class is the last line of defence.
#pragma once

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "ax/core/memory_bus.h"

namespace ax::engine {

enum class PatchMode : int {
    EveryFrame = 0,  // re-applied after each frame (e.g. "infinite lives")
    Once = 1,        // applied a single time, as soon as `expect` matches
};

enum class CompareOp : int { None = 0, Equal = 1, NotEqual = 2, Less = 3, Greater = 4 };

struct PatchCondition {
    CompareOp op = CompareOp::None;
    uint32_t address = 0;
    uint8_t width = 1;  // 1, 2 or 4 bytes
    uint32_t value = 0;
};

struct MemoryPatch {
    std::string id;
    uint32_t address = 0;
    std::vector<uint8_t> bytes;
    std::vector<uint8_t> expect;  // optional original bytes
    PatchMode mode = PatchMode::EveryFrame;
    PatchCondition condition;
    bool enabled = true;
};

struct RomPatch {
    std::string id;
    uint32_t offset = 0;  // file offset (0 = start of ROM)
    std::vector<uint8_t> bytes;
    std::vector<uint8_t> expect;  // optional original bytes
};

enum class PatchState : int {
    Pending = 0,   // waiting for its condition / expected bytes
    Active = 1,    // being applied every frame
    Applied = 2,   // one-shot patch done (or ROM patch applied)
    Skipped = 3,   // expected bytes did not match (wrong ROM revision)
    Failed = 4,    // invalid definition (bad address/size)
    Disabled = 5,  // turned off by the user or by the engine
};

struct PatchStatus {
    std::string id;
    PatchState state = PatchState::Pending;
    uint32_t applyCount = 0;
    std::string message;
};

/// Applies ROM patches to an in-memory ROM image. Never touches files.
std::vector<PatchStatus> applyRomPatches(std::vector<uint8_t>* rom, const std::vector<RomPatch>& patches);

/// Returns an empty string if the memory patch is acceptable, else the reason.
std::string validateMemoryPatch(const MemoryPatch& patch);

class PatchEngine {
public:
    void setPatches(std::vector<MemoryPatch> patches);
    void clear();
    /// Enables/disables a patch by id. Returns false if unknown.
    bool setEnabled(const std::string& id, bool enabled);
    /// Applies due patches. Call on the emulation thread after each frame.
    void onFrame(core::MemoryBus& bus);
    std::vector<PatchStatus> status() const;
    size_t size() const;

private:
    struct Entry {
        MemoryPatch patch;
        PatchStatus status;
    };
    bool conditionHolds(core::MemoryBus& bus, const PatchCondition& c) const;

    mutable std::mutex mutex_;
    std::vector<Entry> entries_;
};

}  // namespace ax::engine
