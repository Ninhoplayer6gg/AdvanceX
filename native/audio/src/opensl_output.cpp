// SPDX-License-Identifier: MPL-2.0
// OpenSL ES output: fallback for devices where AAudio is unavailable or
// unreliable (Android 8.0 and earlier).
#if defined(__ANDROID__)
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>

#include <vector>

#include "ax/audio/audio_output.h"
#include "ax/common/log.h"

namespace ax::audio {
namespace {
constexpr const char* kTag = "OpenSLES";
constexpr int kBufferCount = 3;

class OpenSlOutput final : public AudioOutput {
public:
    ~OpenSlOutput() override { close(); }

    const char* name() const override { return "opensles"; }

    bool open(const OutputConfig& config, AudioSource* source, std::string* error) override {
        close();
        source_ = source;
        sampleRate_ = config.preferredSampleRate > 0 ? config.preferredSampleRate : 48000;
        framesPerBuffer_ = config.lowLatency ? sampleRate_ / 100 : sampleRate_ / 50;  // 10 ms / 20 ms
        for (auto& b : buffers_) b.assign(static_cast<size_t>(framesPerBuffer_) * 2, 0);

        auto fail = [&](const char* what) {
            if (error) *error = what;
            close();
            return false;
        };
        if (slCreateEngine(&engineObj_, 0, nullptr, 0, nullptr, nullptr) != SL_RESULT_SUCCESS) return fail("slCreateEngine");
        if ((*engineObj_)->Realize(engineObj_, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS) return fail("engine Realize");
        if ((*engineObj_)->GetInterface(engineObj_, SL_IID_ENGINE, &engine_) != SL_RESULT_SUCCESS) return fail("engine itf");
        if ((*engine_)->CreateOutputMix(engine_, &mixObj_, 0, nullptr, nullptr) != SL_RESULT_SUCCESS) return fail("output mix");
        if ((*mixObj_)->Realize(mixObj_, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS) return fail("mix Realize");

        SLDataLocator_AndroidSimpleBufferQueue queueLoc = {SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, kBufferCount};
        SLDataFormat_PCM format = {SL_DATAFORMAT_PCM, 2, static_cast<SLuint32>(sampleRate_) * 1000,
                                   SL_PCMSAMPLEFORMAT_FIXED_16, SL_PCMSAMPLEFORMAT_FIXED_16,
                                   SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT, SL_BYTEORDER_LITTLEENDIAN};
        SLDataSource audioSrc = {&queueLoc, &format};
        SLDataLocator_OutputMix mixLoc = {SL_DATALOCATOR_OUTPUTMIX, mixObj_};
        SLDataSink audioSink = {&mixLoc, nullptr};
        const SLInterfaceID ids[] = {SL_IID_BUFFERQUEUE};
        const SLboolean required[] = {SL_BOOLEAN_TRUE};
        if ((*engine_)->CreateAudioPlayer(engine_, &playerObj_, &audioSrc, &audioSink, 1, ids, required) !=
            SL_RESULT_SUCCESS)
            return fail("CreateAudioPlayer");
        if ((*playerObj_)->Realize(playerObj_, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS) return fail("player Realize");
        if ((*playerObj_)->GetInterface(playerObj_, SL_IID_PLAY, &play_) != SL_RESULT_SUCCESS) return fail("play itf");
        if ((*playerObj_)->GetInterface(playerObj_, SL_IID_BUFFERQUEUE, &queue_) != SL_RESULT_SUCCESS)
            return fail("queue itf");
        if ((*queue_)->RegisterCallback(queue_, &OpenSlOutput::queueCallback, this) != SL_RESULT_SUCCESS)
            return fail("RegisterCallback");
        AX_LOGI(kTag, "Opened: %d Hz, %d frames x %d buffers", sampleRate_, framesPerBuffer_, kBufferCount);
        return true;
    }

    bool start() override {
        if (!play_) return false;
        if (!primed_) {
            for (int i = 0; i < kBufferCount; ++i) enqueueNext();
            primed_ = true;
        }
        return (*play_)->SetPlayState(play_, SL_PLAYSTATE_PLAYING) == SL_RESULT_SUCCESS;
    }

    void pause() override {
        if (play_) (*play_)->SetPlayState(play_, SL_PLAYSTATE_PAUSED);
    }

    void close() override {
        if (play_) (*play_)->SetPlayState(play_, SL_PLAYSTATE_STOPPED);
        if (playerObj_) (*playerObj_)->Destroy(playerObj_);
        if (mixObj_) (*mixObj_)->Destroy(mixObj_);
        if (engineObj_) (*engineObj_)->Destroy(engineObj_);
        playerObj_ = mixObj_ = engineObj_ = nullptr;
        engine_ = nullptr;
        play_ = nullptr;
        queue_ = nullptr;
        primed_ = false;
    }

    int sampleRate() const override { return sampleRate_; }

private:
    static void queueCallback(SLAndroidSimpleBufferQueueItf, void* user) {
        static_cast<OpenSlOutput*>(user)->enqueueNext();
    }

    void enqueueNext() {
        auto& buffer = buffers_[next_];
        next_ = (next_ + 1) % kBufferCount;
        source_->render(buffer.data(), framesPerBuffer_);
        (*queue_)->Enqueue(queue_, buffer.data(), static_cast<SLuint32>(buffer.size() * sizeof(int16_t)));
    }

    SLObjectItf engineObj_ = nullptr;
    SLEngineItf engine_ = nullptr;
    SLObjectItf mixObj_ = nullptr;
    SLObjectItf playerObj_ = nullptr;
    SLPlayItf play_ = nullptr;
    SLAndroidSimpleBufferQueueItf queue_ = nullptr;
    AudioSource* source_ = nullptr;
    std::vector<int16_t> buffers_[kBufferCount];
    int next_ = 0;
    int sampleRate_ = 48000;
    int framesPerBuffer_ = 480;
    bool primed_ = false;
};

}  // namespace

std::unique_ptr<AudioOutput> createOpenSlOutput() { return std::make_unique<OpenSlOutput>(); }

}  // namespace ax::audio
#endif
