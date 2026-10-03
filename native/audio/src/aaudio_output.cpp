// SPDX-License-Identifier: MPL-2.0
// AAudio output (Android 8.0+, used on 8.1+ where it is reliable).
#if defined(__ANDROID__)
#include <aaudio/AAudio.h>

#include <atomic>

#include "ax/audio/audio_output.h"
#include "ax/common/log.h"

namespace ax::audio {
namespace {
constexpr const char* kTag = "AAudio";

class AAudioOutput final : public AudioOutput {
public:
    ~AAudioOutput() override { close(); }

    const char* name() const override { return "aaudio"; }

    bool open(const OutputConfig& config, AudioSource* source, std::string* error) override {
        close();
        source_ = source;
        AAudioStreamBuilder* builder = nullptr;
        aaudio_result_t result = AAudio_createStreamBuilder(&builder);
        if (result != AAUDIO_OK) {
            if (error) *error = AAudio_convertResultToText(result);
            return false;
        }
        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setPerformanceMode(
            builder, config.lowLatency ? AAUDIO_PERFORMANCE_MODE_LOW_LATENCY : AAUDIO_PERFORMANCE_MODE_POWER_SAVING);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
        AAudioStreamBuilder_setChannelCount(builder, 2);
        // Leave the sample rate unspecified: the device's native rate avoids
        // an extra resampling stage in the audio HAL. mGBA resamples for us.
        AAudioStreamBuilder_setDataCallback(builder, &AAudioOutput::dataCallback, this);
        AAudioStreamBuilder_setErrorCallback(builder, &AAudioOutput::errorCallback, this);
        result = AAudioStreamBuilder_openStream(builder, &stream_);
        AAudioStreamBuilder_delete(builder);
        if (result != AAUDIO_OK || !stream_) {
            if (error) *error = AAudio_convertResultToText(result);
            stream_ = nullptr;
            return false;
        }
        sampleRate_ = AAudioStream_getSampleRate(stream_);
        int32_t burst = AAudioStream_getFramesPerBurst(stream_);
        if (burst > 0) {
            // Two bursts of device buffering; the rest lives in our ring.
            AAudioStream_setBufferSizeInFrames(stream_, burst * (config.lowLatency ? 2 : 4));
        }
        disconnected_.store(false);
        AX_LOGI(kTag, "Opened: %d Hz, burst %d frames, %s", sampleRate_, burst,
                config.lowLatency ? "low-latency" : "power-saving");
        return true;
    }

    bool start() override {
        return stream_ && AAudioStream_requestStart(stream_) == AAUDIO_OK;
    }

    void pause() override {
        if (stream_) AAudioStream_requestPause(stream_);
    }

    void close() override {
        if (stream_) {
            AAudioStream_requestStop(stream_);
            AAudioStream_close(stream_);
            stream_ = nullptr;
        }
    }

    int sampleRate() const override { return sampleRate_; }
    bool needsRestart() const override { return disconnected_.load(); }

private:
    static aaudio_data_callback_result_t dataCallback(AAudioStream*, void* user, void* audioData, int32_t frames) {
        auto* self = static_cast<AAudioOutput*>(user);
        self->source_->render(static_cast<int16_t*>(audioData), frames);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    static void errorCallback(AAudioStream*, void* user, aaudio_result_t error) {
        auto* self = static_cast<AAudioOutput*>(user);
        if (error == AAUDIO_ERROR_DISCONNECTED) {
            self->disconnected_.store(true);
        }
    }

    AAudioStream* stream_ = nullptr;
    AudioSource* source_ = nullptr;
    int sampleRate_ = 48000;
    std::atomic<bool> disconnected_{false};
};

}  // namespace

std::unique_ptr<AudioOutput> createAAudioOutput() { return std::make_unique<AAudioOutput>(); }

}  // namespace ax::audio
#endif
