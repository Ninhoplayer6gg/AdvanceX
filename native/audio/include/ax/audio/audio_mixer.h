// SPDX-License-Identifier: MPL-2.0
// Bridges the emulation thread (producer) and the audio callback (consumer).
//
// - lock-free SPSC ring of stereo int16 frames
// - volume / mute applied in the callback (never inside the emulator)
// - underruns are filled with silence and counted for diagnostics
// - flush requests are executed on the consumer side, keeping the ring
//   strictly single-producer/single-consumer
#pragma once

#include <atomic>
#include <cstdint>

#include "ax/audio/audio_output.h"
#include "ax/common/spsc_ring.h"

namespace ax::audio {

struct StereoFrame {
    int16_t left;
    int16_t right;
};

class AudioMixer final : public AudioSource {
public:
    explicit AudioMixer(size_t capacityFrames = 16384);

    // --- Producer (emulation thread) ---
    size_t push(const int16_t* interleaved, size_t frames);
    size_t bufferedFrames() const { return ring_.size(); }
    size_t capacityFrames() const { return ring_.capacity(); }
    /// Asks the consumer to drop everything buffered (e.g. when entering
    /// fast-forward or after loading a state).
    void requestFlush() { flushRequested_.store(true, std::memory_order_release); }

    // --- Settings (any thread) ---
    void setVolume(float volume);  // 0..1
    void setMuted(bool muted) { muted_.store(muted, std::memory_order_relaxed); }

    // --- Diagnostics ---
    uint64_t underrunFrames() const { return underrunFrames_.load(std::memory_order_relaxed); }

    // --- Consumer (audio thread) ---
    void render(int16_t* interleavedStereo, int32_t frames) override;

private:
    SpscRing<StereoFrame> ring_;
    std::atomic<bool> flushRequested_{false};
    std::atomic<int32_t> volumeQ15_{32767};
    std::atomic<bool> muted_{false};
    std::atomic<uint64_t> underrunFrames_{0};
};

}  // namespace ax::audio
