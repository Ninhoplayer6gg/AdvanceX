// SPDX-License-Identifier: MPL-2.0
// Hands finished frames from the emulation thread to the render thread.
//
// The producer never waits for the consumer: if rendering falls behind, old
// frames are simply replaced ("latest frame wins"), so a slow GPU can never
// slow down emulation or audio. Buffers are reused, so steady-state
// operation performs no allocations.
#pragma once

#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <vector>

namespace ax::runtime {

/// A replacement overlay to draw for this frame (Advance Engine).
struct ReplacementDraw {
    uint64_t key = 0;
    float x = 0, y = 0, width = 0, height = 0;
    bool hflip = false;
    bool vflip = false;
};

struct FramePacket {
    std::vector<uint32_t> pixels;  // 240x160 RGBX
    std::vector<ReplacementDraw> overlays;
    uint64_t sequence = 0;
};

class FrameMailbox {
public:
    void publish(const uint32_t* pixels, size_t count, const std::vector<ReplacementDraw>& overlays);
    /// Copies the newest frame into `out` if it is newer than out->sequence.
    bool fetchIfNewer(FramePacket* out);
    /// Waits until a frame newer than `sequence` exists or the timeout expires.
    bool waitForNewer(uint64_t sequence, std::chrono::milliseconds timeout);
    void wakeAll();
    uint64_t sequence() const;

private:
    mutable std::mutex mutex_;
    std::condition_variable cv_;
    FramePacket latest_;
};

}  // namespace ax::runtime
