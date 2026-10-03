// SPDX-License-Identifier: MPL-2.0
#include "ax/runtime/session.h"

#include <pthread.h>
#include <sys/resource.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <future>

#include "ax/common/file_io.h"
#include "ax/common/hash.h"
#include "ax/common/log.h"
#include "ax/runtime/crash_handler.h"
#include "ax/runtime/state_file.h"

namespace ax::runtime {
namespace {

constexpr const char* kTag = "Session";
constexpr size_t kMaxRomSize = 32u << 20;
constexpr size_t kBiosSize = 16384;
constexpr size_t kAudioScratchFrames = 4096;
constexpr auto kFastForwardPublishInterval = std::chrono::milliseconds(15);

using Clock = std::chrono::steady_clock;

int64_t nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch())
        .count();
}

void configureEmulationThread() {
#if defined(__linux__) || defined(__ANDROID__)
    pthread_setname_np(pthread_self(), "ax-emu");
    // Equivalent of Android's THREAD_PRIORITY_DISPLAY (-4) / URGENT_DISPLAY
    // (-8). Failure is harmless (e.g. on desktop without CAP_SYS_NICE).
    setpriority(PRIO_PROCESS, static_cast<id_t>(gettid()), -8);
#endif
}

}  // namespace

Session::Session() : mixer_(16384) {}

Session::~Session() { close(); }

bool Session::open(const LaunchParams& params, std::unique_ptr<audio::AudioOutput> output, std::string* error) {
    if (core_) {
        if (error) *error = "session already open";
        return false;
    }
    params_ = params;
    advanceMode_ = params.advanceMode;

    std::vector<uint8_t> rom;
    if (!ax::fileio::readFile(params.romPath, &rom, kMaxRomSize) || rom.size() < 0xC0) {
        if (error) *error = "Cannot read the ROM file (missing, unreadable or larger than 32 MiB).";
        return false;
    }
    // Identity of the *original* image: patches never change it.
    romCrc32_ = ax::hash::crc32(rom.data(), rom.size());
    romSize_ = static_cast<uint32_t>(rom.size());

    if (advanceMode_ && !params.romPatches.empty()) {
        romPatchStatus_ = engine::applyRomPatches(&rom, params.romPatches);
    }

    core::LoadRequest request;
    request.rom = std::move(rom);
    if (!params.savePath.empty()) {
        bool usedBackup = false;
        if (ax::fileio::readFileWithBackup(params.savePath, &request.saveData, &usedBackup)) {
            if (usedBackup) {
                AX_LOGW(kTag, "Primary save was unreadable; restored the backup copy (%s.bak)", params.savePath.c_str());
            }
            lastPersistedSave_ = request.saveData;
        }
    }
    if (!params.biosPath.empty()) {
        std::vector<uint8_t> bios;
        if (ax::fileio::readFile(params.biosPath, &bios) && bios.size() == kBiosSize) {
            request.bios = std::move(bios);
        } else {
            AX_LOGW(kTag, "Ignoring BIOS file %s (missing or not 16 KiB)", params.biosPath.c_str());
        }
    }

    core_ = core::createMgbaCore();
    std::string loadError;
    if (!core_->load(std::move(request), &loadError)) {
        if (error) *error = "The emulator could not start this ROM: " + loadError;
        core_.reset();
        return false;
    }
    header_ = core_->header();
    if (advanceMode_) {
        patches_.setPatches(params.memoryPatches);
    }

    output_ = output ? std::move(output) : audio::createAudioOutput(audio::Backend::Null);
    std::string audioError;
    if (!output_->open(audio::OutputConfig{48000, lowLatency_}, &mixer_, &audioError)) {
        AX_LOGE(kTag, "Audio output '%s' failed (%s); continuing without sound", output_->name(), audioError.c_str());
        output_ = audio::createAudioOutput(audio::Backend::Null);
        output_->open(audio::OutputConfig{48000, lowLatency_}, &mixer_, &audioError);
    }
    sampleRate_ = output_->sampleRate();
    audioRealtime_ = output_->isRealtime();
    core_->setAudioSampleRate(sampleRate_.load());
    latencyFrames_ = sampleRate_.load() * 64 / 1000;
    audioScratch_.assign(kAudioScratchFrames * 2, 0);

    rewind_.configure(engine::RewindConfig{}, core_->framesPerSecond());
    writer_ = std::make_unique<BackgroundWriter>([this](const std::string& path, bool ok, const std::string&) {
        if (path != params_.savePath) return;
        saveWriteFailed_.store(!ok);
        if (!ok) saveRetryNeeded_.store(true);
    });

    std::string context = "game=" + params.gameId + " code=" + header_.gameCode +
                          " advance=" + (advanceMode_ ? "on" : "off") +
                          " patches=" + std::to_string(params.memoryPatches.size() + params.romPatches.size());
    setCrashContext(context);
    AX_LOGI(kTag, "Opened %s (crc32 %08x) audio=%s@%dHz advance=%s", header_.gameCode.c_str(), romCrc32_,
            output_->name(), sampleRate_.load(), advanceMode_ ? "on" : "off");
    return true;
}

void Session::start() {
    if (!core_ || running_.load()) return;
    stop_ = false;
    running_ = true;
    if (!paused_) output_->start();
    thread_ = std::thread([this] { threadMain(); });
}

void Session::close() {
    if (thread_.joinable()) {
        stop_ = true;
        commandCv_.notify_all();
        thread_.join();
    }
    running_ = false;
    if (core_) {
        persistSaveIfDirty(true);
    }
    if (writer_) {
        writer_->drain();
        writer_.reset();
    }
    if (output_) {
        output_->close();
        output_.reset();
    }
    if (core_) {
        core_->unload();
        core_.reset();
        setCrashContext("no game running");
        AX_LOGI(kTag, "Session closed");
    }
}

// --- Command plumbing --------------------------------------------------------

void Session::post(std::function<void()> fn) {
    if (!running_.load()) {
        fn();
        return;
    }
    {
        std::lock_guard<std::mutex> lock(commandMutex_);
        commands_.push_back(std::move(fn));
    }
    commandCv_.notify_all();
}

bool Session::runOnEmuThread(std::function<void()> fn, int timeoutMs) {
    if (!running_.load() || std::this_thread::get_id() == thread_.get_id()) {
        fn();
        return true;
    }
    auto done = std::make_shared<std::promise<void>>();
    std::future<void> future = done->get_future();
    post([fn = std::move(fn), done] {
        fn();
        done->set_value();
    });
    if (future.wait_for(std::chrono::milliseconds(timeoutMs)) != std::future_status::ready) {
        AX_LOGE(kTag, "Emulation thread did not answer within %d ms", timeoutMs);
        return false;
    }
    return true;
}

void Session::processCommands() {
    std::deque<std::function<void()>> pending;
    {
        std::lock_guard<std::mutex> lock(commandMutex_);
        pending.swap(commands_);
    }
    for (auto& fn : pending) fn();
}

// --- Emulation thread ----------------------------------------------------------

void Session::threadMain() {
    configureEmulationThread();
    const double fps = core_->framesPerSecond();
    auto next = Clock::now();
    auto statsStart = Clock::now();
    uint32_t statsFrames = 0;

    while (!stop_.load()) {
        processCommands();
        if (paused_.load()) {
            std::unique_lock<std::mutex> lock(commandMutex_);
            commandCv_.wait_for(lock, std::chrono::milliseconds(100),
                                [&] { return stop_.load() || !commands_.empty() || !paused_.load(); });
            next = Clock::now();
            statsStart = next;
            statsFrames = 0;
            continue;
        }
        restartAudioIfNeeded();

        if (rewinding_.load()) {
            if (rewind_.pop(&scratchState_)) {
                core_->loadState(scratchState_.data(), scratchState_.size());
                core_->setKeys(0);
                core_->runFrame();
                // Rewind is silent: discard what the frame produced.
                while (core_->drainAudio(audioScratch_.data(), kAudioScratchFrames) > 0) {
                }
                auto f = core_->frame();
                mailbox_.publish(f.pixels, static_cast<size_t>(f.width) * f.height, {});
                rewindSeconds_ = rewind_.secondsStored();
                rewindBytes_ = rewind_.memoryUsage();
                framesSinceSnapshot_ = 0;
            }
            mixer_.requestFlush();
            next += std::chrono::duration_cast<Clock::duration>(std::chrono::duration<double>(1.0 / fps));
            if (next < Clock::now()) next = Clock::now();
            std::this_thread::sleep_until(next);
            continue;
        }

        runFrame();
        ++statsFrames;
        paceFrame(&next, fps);

        auto now = Clock::now();
        double elapsed = std::chrono::duration<double>(now - statsStart).count();
        if (elapsed >= 1.0) {
            fps_ = statsFrames / elapsed;
            statsFrames = 0;
            statsStart = now;
        }
    }
    output_->pause();
}

void Session::runFrame() {
    core_->setKeys(keys_.load(std::memory_order_relaxed));
    core_->runFrame();
    ++frameIndex_;
    frameCounter_ = core_->frameCounter();

    if (advanceMode_) {
        patches_.onFrame(core_->memory());
    }

    if (rewind_.config().enabled && ++framesSinceSnapshot_ >= rewind_.config().intervalFrames) {
        framesSinceSnapshot_ = 0;
        if (core_->saveState(&scratchState_)) {
            rewind_.push(scratchState_);
            rewindSeconds_ = rewind_.secondsStored();
            rewindBytes_ = rewind_.memoryUsage();
        }
    }

    // Audio: always drain the core; only keep it at normal speed.
    bool keepAudio = !fastForward_.load(std::memory_order_relaxed);
    size_t got;
    while ((got = core_->drainAudio(audioScratch_.data(), kAudioScratchFrames)) > 0) {
        if (keepAudio) mixer_.push(audioScratch_.data(), got);
    }

    // Video
    bool publish = true;
    int skip = frameSkip_.load(std::memory_order_relaxed);
    if (skip > 0 && frameIndex_ % static_cast<uint32_t>(skip + 1) != 0) publish = false;
    auto now = Clock::now();
    if (fastForward_.load(std::memory_order_relaxed) && now - lastPublish_ < kFastForwardPublishInterval) {
        publish = false;
    }
    if (publish) {
        drawScratch_.clear();
        if (assetsActive_.load(std::memory_order_relaxed)) detectAssets(&drawScratch_);
        auto f = core_->frame();
        mailbox_.publish(f.pixels, static_cast<size_t>(f.width) * f.height, drawScratch_);
        lastPublish_ = now;
    }

    persistSaveIfDirty(false);
    if (saveRetryNeeded_.load(std::memory_order_relaxed) && frameIndex_ % 300 == 0) {
        saveRetryNeeded_ = false;
        lastPersistedSave_.clear();  // force a rewrite
        persistSaveIfDirty(true);
    }
}

void Session::paceFrame(Clock::time_point* next, double fps) {
    const auto period = std::chrono::duration_cast<Clock::duration>(std::chrono::duration<double>(1.0 / fps));
    auto now = Clock::now();

    if (fastForward_.load(std::memory_order_relaxed)) {
        int mult = ffMultiplier_.load();
        if (mult <= 0) {
            *next = now;  // unlimited
            return;
        }
        *next += period / mult;
        if (*next < now - std::chrono::milliseconds(100)) *next = now;
        std::this_thread::sleep_until(*next);
        return;
    }

    if (audioRealtime_) {
        // Audio clock drives emulation speed: wait until the device has
        // consumed enough that the buffer is back at its target level.
        const size_t target = static_cast<size_t>(std::max(256, latencyFrames_.load()));
        const int rate = std::max(8000, sampleRate_.load());
        auto deadline = now + std::chrono::milliseconds(100);
        bool stalled = false;
        while (!stop_.load() && !paused_.load()) {
            size_t buffered = mixer_.bufferedFrames();
            if (buffered <= target) break;
            if (Clock::now() > deadline) {
                stalled = true;
                break;
            }
            double excess = static_cast<double>(buffered - target) / rate;
            auto wait = std::chrono::duration<double>(std::clamp(excess, 0.0005, 0.008));
            std::this_thread::sleep_for(wait);
        }
        if (stalled && ++audioStallFrames_ > 30) {
            AX_LOGW(kTag, "Audio device stopped consuming samples; pacing with a timer instead");
            audioRealtime_ = false;
        } else if (!stalled) {
            audioStallFrames_ = 0;
        }
        *next = Clock::now();
        return;
    }

    *next += period;
    if (*next < now - std::chrono::milliseconds(100)) *next = now;
    std::this_thread::sleep_until(*next);
}

void Session::restartAudioIfNeeded() {
    if (!output_ || !output_->needsRestart()) return;
    AX_LOGW(kTag, "Audio route changed; reopening %s", output_->name());
    output_->close();
    std::string err;
    if (output_->open(audio::OutputConfig{48000, lowLatency_}, &mixer_, &err) && output_->start()) {
        sampleRate_ = output_->sampleRate();
        core_->setAudioSampleRate(sampleRate_.load());
        mixer_.requestFlush();
        return;
    }
    AX_LOGE(kTag, "Could not reopen audio (%s); continuing silently", err.c_str());
    output_ = audio::createAudioOutput(audio::Backend::Null);
    output_->open(audio::OutputConfig{48000, lowLatency_}, &mixer_, &err);
    audioRealtime_ = false;
}

void Session::persistSaveIfDirty(bool force) {
    if (!core_ || params_.savePath.empty() || !writer_) return;
    bool dirty = core_->consumeSaveDirty();
    if (!dirty && !force) return;
    std::vector<uint8_t> data = core_->saveData();
    if (data.empty() || data == lastPersistedSave_) return;
    writer_->submit(params_.savePath, data, true);
    lastPersistedSave_ = std::move(data);
}

void Session::detectAssets(std::vector<ReplacementDraw>* draws) {
    detector_.scan(core_->memory(), &spriteScratch_);
    std::lock_guard<std::mutex> lock(assetMutex_);
    for (const engine::SpriteInstance& s : spriteScratch_) {
        uint64_t key = engine::replacementKey(s);
        if (!s.affine && replacementKeys_.count(key)) {
            draws->push_back(ReplacementDraw{key, static_cast<float>(s.x), static_cast<float>(s.y),
                                             static_cast<float>(s.width), static_cast<float>(s.height), s.hflip,
                                             s.vflip});
        }
        if (dumpEnabled_ && dumpQueue_.size() < 256 && dumpedKeys_.insert(key).second) {
            DumpedSprite dumped;
            dumped.key = key;
            dumped.width = s.width;
            dumped.height = s.height;
            if (detector_.decode(core_->memory(), s, &dumped.rgba)) dumpQueue_.push_back(std::move(dumped));
        }
    }
}

// --- Public controls -----------------------------------------------------------

void Session::setPaused(bool paused) {
    if (paused_.exchange(paused) == paused) return;
    commandCv_.notify_all();
    post([this, paused] {
        if (!output_) return;
        if (paused) {
            output_->pause();
            persistSaveIfDirty(true);  // app may be about to be killed
        } else {
            mixer_.requestFlush();
            output_->start();
        }
    });
}

void Session::setFastForward(bool active, int multiplier) {
    ffMultiplier_ = std::clamp(multiplier, 0, 16);
    if (fastForward_.exchange(active) != active) mixer_.requestFlush();
}

void Session::setRewindConfig(const engine::RewindConfig& config) {
    post([this, config] {
        rewind_.configure(config, core_ ? core_->framesPerSecond() : 59.7275);
        framesSinceSnapshot_ = 0;
        rewindSeconds_ = rewind_.secondsStored();
        rewindBytes_ = rewind_.memoryUsage();
    });
}

void Session::setAudioSettings(const AudioSettings& s) {
    mixer_.setVolume(s.volume);
    mixer_.setMuted(s.muted);
    int rate = sampleRate_.load();
    int frames = rate * std::clamp(s.latencyMs, 20, 250) / 1000;
    latencyFrames_ = std::min(frames, static_cast<int>(mixer_.capacityFrames()) / 2);
    if (s.lowLatency != lowLatency_) {
        post([this, low = s.lowLatency] {
            lowLatency_ = low;
            if (!output_ || !output_->isRealtime()) return;
            output_->close();
            std::string err;
            if (output_->open(audio::OutputConfig{48000, lowLatency_}, &mixer_, &err)) {
                sampleRate_ = output_->sampleRate();
                if (core_) core_->setAudioSampleRate(sampleRate_.load());
                if (!paused_.load()) output_->start();
            }
        });
    }
}

void Session::setFrameSkip(int frames) { frameSkip_ = std::clamp(frames, 0, 3); }

bool Session::setPatchEnabled(const std::string& id, bool enabled) {
    if (!advanceMode_) return false;
    return patches_.setEnabled(id, enabled);
}

std::vector<engine::PatchStatus> Session::patchStatus() const {
    std::vector<engine::PatchStatus> all = romPatchStatus_;
    auto mem = patches_.status();
    all.insert(all.end(), mem.begin(), mem.end());
    return all;
}

void Session::setReplacementKeys(const std::vector<uint64_t>& keys) {
    std::lock_guard<std::mutex> lock(assetMutex_);
    replacementKeys_.clear();
    replacementKeys_.insert(keys.begin(), keys.end());
    assetsActive_ = advanceMode_ && (!replacementKeys_.empty() || dumpEnabled_);
}

void Session::setAssetDumpEnabled(bool enabled) {
    std::lock_guard<std::mutex> lock(assetMutex_);
    dumpEnabled_ = enabled;
    assetsActive_ = advanceMode_ && (!replacementKeys_.empty() || dumpEnabled_);
}

std::vector<DumpedSprite> Session::takeDumpedSprites() {
    std::lock_guard<std::mutex> lock(assetMutex_);
    std::vector<DumpedSprite> out;
    out.swap(dumpQueue_);
    return out;
}

// --- Blocking commands -------------------------------------------------------

bool Session::saveState(const std::string& path, std::string* error) {
    if (!core_) {
        if (error) *error = "no game running";
        return false;
    }
    struct Result {
        bool ok = false;
        std::vector<uint8_t> state;
        uint32_t frame = 0;
    };
    auto result = std::make_shared<Result>();
    if (!runOnEmuThread([this, result] {
            result->ok = core_->saveState(&result->state);
            result->frame = core_->frameCounter();
        })) {
        if (error) *error = "emulation thread is not responding";
        return false;
    }
    if (!result->ok) {
        if (error) *error = "the core could not serialize its state";
        return false;
    }
    StateFileInfo info;
    info.coreId = core_->id();
    info.coreVersion = core_->version();
    info.romCrc32 = romCrc32_;
    info.romSize = romSize_;
    info.timestampMs = nowMs();
    info.frameCounter = result->frame;
    if (!writeStateFile(path, result->state, info, error)) return false;
    AX_LOGI(kTag, "Saved state %s", path.c_str());
    return true;
}

bool Session::loadState(const std::string& path, std::string* error) {
    if (!core_) {
        if (error) *error = "no game running";
        return false;
    }
    auto state = std::make_shared<std::vector<uint8_t>>();
    StateFileInfo info;
    if (!readStateFile(path, state.get(), &info, error)) return false;
    if (info.romCrc32 != romCrc32_ || info.romSize != romSize_) {
        if (error) *error = "This save state was made with a different game or ROM revision.";
        return false;
    }
    if (info.coreId != core_->id()) {
        if (error) *error = "This save state was made by a different emulator core (" + info.coreId + ").";
        return false;
    }
    auto ok = std::make_shared<bool>(false);
    if (!runOnEmuThread([this, state, ok] {
            std::vector<uint8_t> backup;
            bool haveBackup = core_->saveState(&backup);
            *ok = core_->loadState(state->data(), state->size());
            if (!*ok && haveBackup) {
                // Never leave the game in a half-loaded state.
                core_->loadState(backup.data(), backup.size());
            }
            mixer_.requestFlush();
            framesSinceSnapshot_ = 0;
        })) {
        if (error) *error = "emulation thread is not responding";
        return false;
    }
    if (!*ok) {
        if (error) *error = "The state file is incompatible with this version of the core.";
        return false;
    }
    AX_LOGI(kTag, "Loaded state %s", path.c_str());
    return true;
}

bool Session::reset() {
    if (!core_) return false;
    return runOnEmuThread([this] {
        core_->reset();
        mixer_.requestFlush();
        rewind_.clear();
    });
}

bool Session::flushSave(std::string* error) {
    if (!core_) return true;
    if (!runOnEmuThread([this] { persistSaveIfDirty(true); })) {
        if (error) *error = "emulation thread is not responding";
        return false;
    }
    if (writer_) writer_->drain();
    if (saveWriteFailed_.load()) {
        if (error) *error = "The cartridge save could not be written to storage.";
        return false;
    }
    return true;
}

bool Session::readMemory(uint32_t address, size_t size, std::vector<uint8_t>* out) {
    if (!core_ || size > (1u << 20)) return false;
    auto buffer = std::make_shared<std::vector<uint8_t>>(size);
    if (!runOnEmuThread([this, buffer, address] {
            auto& bus = core_->memory();
            for (size_t i = 0; i < buffer->size(); ++i) (*buffer)[i] = bus.read8(address + static_cast<uint32_t>(i));
        })) {
        return false;
    }
    out->swap(*buffer);
    return true;
}

bool Session::copyFrame(std::vector<uint32_t>* rgba) {
    FramePacket packet;
    if (!mailbox_.fetchIfNewer(&packet)) return false;
    rgba->swap(packet.pixels);
    for (uint32_t& p : *rgba) p |= 0xFF000000u;  // opaque for image encoders
    return true;
}

SessionStats Session::stats() const {
    SessionStats s;
    s.fps = fps_.load();
    double native = core_ ? core_->framesPerSecond() : 59.7275;
    s.speed = native > 0 ? s.fps / native : 0;
    s.frameCounter = frameCounter_.load();
    s.audioBufferedFrames = mixer_.bufferedFrames();
    s.audioUnderrunFrames = mixer_.underrunFrames();
    s.audioSampleRate = sampleRate_.load();
    s.audioBackend = output_ ? output_->name() : "none";
    s.fastForward = fastForward_.load();
    s.rewinding = rewinding_.load();
    s.rewindSeconds = rewindSeconds_.load();
    s.rewindBytes = rewindBytes_.load();
    s.saveWriteFailed = saveWriteFailed_.load();
    return s;
}

void Session::runFramesForTest(int frames) {
    for (int i = 0; i < frames && core_; ++i) {
        processCommands();
        runFrame();
    }
}

}  // namespace ax::runtime
