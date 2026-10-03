// SPDX-License-Identifier: MPL-2.0
#include "ax/runtime/frame_mailbox.h"

namespace ax::runtime {

void FrameMailbox::publish(const uint32_t* pixels, size_t count, const std::vector<ReplacementDraw>& overlays) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        latest_.pixels.assign(pixels, pixels + count);
        latest_.overlays.assign(overlays.begin(), overlays.end());
        ++latest_.sequence;
    }
    cv_.notify_all();
}

bool FrameMailbox::fetchIfNewer(FramePacket* out) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (latest_.sequence == 0 || latest_.sequence == out->sequence) return false;
    out->pixels.assign(latest_.pixels.begin(), latest_.pixels.end());
    out->overlays.assign(latest_.overlays.begin(), latest_.overlays.end());
    out->sequence = latest_.sequence;
    return true;
}

bool FrameMailbox::waitForNewer(uint64_t sequence, std::chrono::milliseconds timeout) {
    std::unique_lock<std::mutex> lock(mutex_);
    cv_.wait_for(lock, timeout, [&] { return latest_.sequence != sequence || wakeRequested_; });
    wakeRequested_ = false;
    return latest_.sequence != sequence;
}

void FrameMailbox::wakeAll() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        wakeRequested_ = true;
    }
    cv_.notify_all();
}

uint64_t FrameMailbox::sequence() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return latest_.sequence;
}

}  // namespace ax::runtime
