// SPDX-License-Identifier: MPL-2.0
#pragma once

#include "ax/audio/audio_output.h"

namespace ax::audio {

/// Discards audio. Used on hosts without an audio device and as the last
/// fallback on Android; the runtime then paces emulation with a timer.
class NullOutput final : public AudioOutput {
public:
    const char* name() const override { return "null"; }
    bool open(const OutputConfig& config, AudioSource* source, std::string* error) override;
    bool start() override { return true; }
    void pause() override {}
    void close() override {}
    int sampleRate() const override { return sampleRate_; }
    bool isRealtime() const override { return false; }

private:
    int sampleRate_ = 48000;
};

}  // namespace ax::audio
