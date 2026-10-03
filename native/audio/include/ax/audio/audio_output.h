// SPDX-License-Identifier: MPL-2.0
// Platform audio output abstraction.
//
// Implementations: AAudio (Android 8.1+), OpenSL ES (older devices / fallback)
// and a null output (hosts, or when no audio device can be opened — the
// runtime then paces emulation with a timer instead of the audio clock).
#pragma once

#include <cstdint>
#include <memory>
#include <string>

namespace ax::audio {

/// Pulled from the real-time audio thread. Must not block or allocate.
class AudioSource {
public:
    virtual ~AudioSource() = default;
    virtual void render(int16_t* interleavedStereo, int32_t frames) = 0;
};

enum class Backend : int { Auto = 0, AAudio = 1, OpenSLES = 2, Null = 3 };

struct OutputConfig {
    int preferredSampleRate = 48000;
    /// Low-latency mode wakes the CPU more often; power-saving mode uses
    /// larger bursts (Battery Saver performance profile).
    bool lowLatency = true;
};

class AudioOutput {
public:
    virtual ~AudioOutput() = default;
    virtual const char* name() const = 0;
    virtual bool open(const OutputConfig& config, AudioSource* source, std::string* error) = 0;
    virtual bool start() = 0;
    virtual void pause() = 0;
    virtual void close() = 0;
    /// Actual device sample rate (valid after open).
    virtual int sampleRate() const = 0;
    /// False for outputs that do not consume audio in real time.
    virtual bool isRealtime() const { return true; }
    /// True when the device went away (headphones unplugged, route change);
    /// the owner should close() and open() again from a normal thread.
    virtual bool needsRestart() const { return false; }
};

/// Creates an output for the requested backend. `Auto` picks the best
/// backend for the running platform/API level.
std::unique_ptr<AudioOutput> createAudioOutput(Backend backend);

}  // namespace ax::audio
