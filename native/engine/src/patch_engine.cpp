// SPDX-License-Identifier: MPL-2.0
#include "ax/engine/patch_engine.h"

#include <cstdio>
#include <cstring>

#include "ax/common/log.h"

namespace ax::engine {
namespace {

constexpr const char* kTag = "PatchEngine";
constexpr size_t kMaxPatchBytes = 4096;
constexpr uint32_t kRomHeaderEnd = 0xC0;

std::string hex(uint32_t v) {
    char buf[16];
    std::snprintf(buf, sizeof(buf), "0x%08X", v);
    return buf;
}

bool isWritableRegion(core::MemoryRegion r) {
    switch (r) {
        case core::MemoryRegion::Ewram:
        case core::MemoryRegion::Iwram:
        case core::MemoryRegion::Palette:
        case core::MemoryRegion::Vram:
        case core::MemoryRegion::Oam:
            return true;
        default:
            return false;  // BIOS, I/O, ROM (use RomPatch), save memory
    }
}

uint32_t readWidth(core::MemoryBus& bus, uint32_t address, uint8_t width) {
    switch (width) {
        case 2: return bus.read16(address);
        case 4: return bus.read32(address);
        default: return bus.read8(address);
    }
}

bool bytesEqual(core::MemoryBus& bus, uint32_t address, const std::vector<uint8_t>& bytes) {
    for (size_t i = 0; i < bytes.size(); ++i) {
        if (bus.read8(address + static_cast<uint32_t>(i)) != bytes[i]) return false;
    }
    return true;
}

}  // namespace

std::vector<PatchStatus> applyRomPatches(std::vector<uint8_t>* rom, const std::vector<RomPatch>& patches) {
    std::vector<PatchStatus> result;
    result.reserve(patches.size());
    for (const RomPatch& p : patches) {
        PatchStatus s;
        s.id = p.id;
        if (p.bytes.empty() || p.bytes.size() > kMaxPatchBytes) {
            s.state = PatchState::Failed;
            s.message = "patch must contain 1..4096 bytes";
        } else if (!p.expect.empty() && p.expect.size() != p.bytes.size()) {
            s.state = PatchState::Failed;
            s.message = "'expect' must have the same length as 'bytes'";
        } else if (p.offset < kRomHeaderEnd) {
            s.state = PatchState::Failed;
            s.message = "the cartridge header (0x00-0xBF) cannot be patched";
        } else if (static_cast<uint64_t>(p.offset) + p.bytes.size() > rom->size()) {
            s.state = PatchState::Failed;
            s.message = "offset " + hex(p.offset) + " is outside the ROM";
        } else {
            uint8_t* at = rom->data() + p.offset;
            if (!p.expect.empty() && std::memcmp(at, p.expect.data(), p.expect.size()) != 0) {
                if (std::memcmp(at, p.bytes.data(), p.bytes.size()) == 0) {
                    s.state = PatchState::Applied;
                    s.message = "already present";
                } else {
                    s.state = PatchState::Skipped;
                    s.message = "bytes at " + hex(p.offset) + " differ from the expected original (different ROM revision?)";
                }
            } else {
                std::memcpy(at, p.bytes.data(), p.bytes.size());
                s.state = PatchState::Applied;
                s.applyCount = 1;
            }
        }
        if (s.state == PatchState::Failed || s.state == PatchState::Skipped) {
            AX_LOGW(kTag, "ROM patch '%s' not applied: %s", p.id.c_str(), s.message.c_str());
        } else {
            AX_LOGI(kTag, "ROM patch '%s' applied at %s (%zu bytes)", p.id.c_str(), hex(p.offset).c_str(),
                    p.bytes.size());
        }
        result.push_back(std::move(s));
    }
    return result;
}

std::string validateMemoryPatch(const MemoryPatch& p) {
    if (p.bytes.empty() || p.bytes.size() > kMaxPatchBytes) return "patch must contain 1..4096 bytes";
    if (!p.expect.empty() && p.expect.size() != p.bytes.size()) return "'expect' must have the same length as 'bytes'";
    core::MemoryRegion region = core::classifyAddress(p.address);
    if (!isWritableRegion(region)) {
        return "address " + hex(p.address) + " is not in writable RAM (EWRAM, IWRAM, palette, VRAM or OAM)";
    }
    const auto& info = core::regionInfo(region);
    if (static_cast<uint64_t>(p.address) + p.bytes.size() > static_cast<uint64_t>(info.base) + info.size) {
        return "patch crosses the end of " + std::string(info.name);
    }
    if (p.condition.op != CompareOp::None) {
        if (p.condition.width != 1 && p.condition.width != 2 && p.condition.width != 4) {
            return "condition width must be 1, 2 or 4";
        }
        if (core::classifyAddress(p.condition.address) == core::MemoryRegion::Invalid) {
            return "condition address " + hex(p.condition.address) + " is unmapped";
        }
    }
    return {};
}

void PatchEngine::setPatches(std::vector<MemoryPatch> patches) {
    std::lock_guard<std::mutex> lock(mutex_);
    entries_.clear();
    for (MemoryPatch& p : patches) {
        Entry e;
        e.status.id = p.id;
        std::string problem = validateMemoryPatch(p);
        if (!problem.empty()) {
            e.status.state = PatchState::Failed;
            e.status.message = problem;
            AX_LOGW(kTag, "Memory patch '%s' rejected: %s", p.id.c_str(), problem.c_str());
        } else if (!p.enabled) {
            e.status.state = PatchState::Disabled;
        }
        e.patch = std::move(p);
        entries_.push_back(std::move(e));
    }
}

void PatchEngine::clear() {
    std::lock_guard<std::mutex> lock(mutex_);
    entries_.clear();
}

bool PatchEngine::setEnabled(const std::string& id, bool enabled) {
    std::lock_guard<std::mutex> lock(mutex_);
    for (Entry& e : entries_) {
        if (e.patch.id != id) continue;
        if (e.status.state == PatchState::Failed) return true;  // stays failed
        e.patch.enabled = enabled;
        if (!enabled) {
            e.status.state = PatchState::Disabled;
        } else if (e.status.state == PatchState::Disabled) {
            e.status.state = PatchState::Pending;
        }
        return true;
    }
    return false;
}

bool PatchEngine::conditionHolds(core::MemoryBus& bus, const PatchCondition& c) const {
    if (c.op == CompareOp::None) return true;
    uint32_t v = readWidth(bus, c.address, c.width);
    switch (c.op) {
        case CompareOp::Equal: return v == c.value;
        case CompareOp::NotEqual: return v != c.value;
        case CompareOp::Less: return v < c.value;
        case CompareOp::Greater: return v > c.value;
        case CompareOp::None: return true;
    }
    return false;
}

void PatchEngine::onFrame(core::MemoryBus& bus) {
    std::lock_guard<std::mutex> lock(mutex_);
    for (Entry& e : entries_) {
        PatchState st = e.status.state;
        if (st == PatchState::Failed || st == PatchState::Disabled || st == PatchState::Skipped ||
            (st == PatchState::Applied && e.patch.mode == PatchMode::Once)) {
            continue;
        }
        const MemoryPatch& p = e.patch;
        if (!conditionHolds(bus, p.condition)) {
            if (p.mode == PatchMode::EveryFrame && st == PatchState::Active) e.status.state = PatchState::Pending;
            continue;
        }
        if (!p.expect.empty() && !bytesEqual(bus, p.address, p.expect) && !bytesEqual(bus, p.address, p.bytes)) {
            // Not (yet) the code/data this patch was written for.
            if (p.mode == PatchMode::EveryFrame) e.status.state = PatchState::Pending;
            continue;
        }
        for (size_t i = 0; i < p.bytes.size(); ++i) {
            bus.write8(p.address + static_cast<uint32_t>(i), p.bytes[i]);
        }
        ++e.status.applyCount;
        e.status.state = p.mode == PatchMode::Once ? PatchState::Applied : PatchState::Active;
    }
}

std::vector<PatchStatus> PatchEngine::status() const {
    std::lock_guard<std::mutex> lock(mutex_);
    std::vector<PatchStatus> out;
    out.reserve(entries_.size());
    for (const Entry& e : entries_) out.push_back(e.status);
    return out;
}

size_t PatchEngine::size() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return entries_.size();
}

}  // namespace ax::engine
