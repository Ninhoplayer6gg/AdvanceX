// SPDX-License-Identifier: MPL-2.0
#include "ax/audio/audio_mixer.h"

#include <algorithm>
#include <cstring>

namespace ax::audio {

AudioMixer::AudioMixer(size_t capacityFrames) : ring_(capacityFrames) {}

size_t AudioMixer::push(const int16_t* interleaved, size_t frames) {
    static_assert(sizeof(StereoFrame) == 2 * sizeof(int16_t), "packed stereo frame");
    return ring_.push(reinterpret_cast<const StereoFrame*>(interleaved), frames);
}

void AudioMixer::setVolume(float volume) {
    volume = std::clamp(volume, 0.0f, 1.0f);
    volumeQ15_.store(static_cast<int32_t>(volume * 32767.0f), std::memory_order_relaxed);
}

void AudioMixer::render(int16_t* out, int32_t frames) {
    if (frames <= 0) return;
    if (flushRequested_.exchange(false, std::memory_order_acq_rel)) {
        ring_.clear();
    }
    auto* dst = reinterpret_cast<StereoFrame*>(out);
    size_t got = ring_.pop(dst, static_cast<size_t>(frames));
    if (got < static_cast<size_t>(frames)) {
        std::memset(dst + got, 0, (static_cast<size_t>(frames) - got) * sizeof(StereoFrame));
        underrunFrames_.fetch_add(static_cast<uint64_t>(frames) - got, std::memory_order_relaxed);
    }
    if (muted_.load(std::memory_order_relaxed)) {
        std::memset(out, 0, static_cast<size_t>(frames) * sizeof(StereoFrame));
        return;
    }
    int32_t gain = volumeQ15_.load(std::memory_order_relaxed);
    if (gain >= 32767) return;
    for (int32_t i = 0; i < frames * 2; ++i) {
        out[i] = static_cast<int16_t>((static_cast<int32_t>(out[i]) * gain) >> 15);
    }
}

}  // namespace ax::audio
