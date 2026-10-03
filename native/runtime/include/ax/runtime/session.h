// SPDX-License-Identifier: MPL-2.0
// An emulation session: one loaded game running on its own thread.
//
// This is the "AdvanceX API" layer that sits between the frontend and the
// emulator core + Advance Engine:
//
//   Android UI ─► JNI ─► Session ─┬─► EmulatorCore (mGBA)
//                                 ├─► Advance Engine (patches, rewind, assets)
//                                 ├─► AudioMixer ─► AudioOutput
//                                 └─► FrameMailbox ─► render thread
//
// Threading: public methods may be called from any thread. Anything that
// touches the core is executed on the emulation thread via a command queue.
// When `advanceMode` is false no engine feature ever touches the core.
#pragma once

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_set>
#include <vector>

#include "ax/audio/audio_mixer.h"
#include "ax/audio/audio_output.h"
#include "ax/core/emulator_core.h"
#include "ax/engine/patch_engine.h"
#include "ax/engine/rewind_buffer.h"
#include "ax/engine/sprite_detector.h"
#include "ax/runtime/background_writer.h"
#include "ax/runtime/frame_mailbox.h"

namespace ax::runtime {

struct LaunchParams {
    std::string romPath;
    std::string savePath;  // cartridge save (written atomically, previous kept as .bak)
    std::string biosPath;  // optional user-supplied BIOS
    std::string gameId;    // used in logs and crash reports
    bool advanceMode = false;
    std::vector<engine::RomPatch> romPatches;        // ignored unless advanceMode
    std::vector<engine::MemoryPatch> memoryPatches;  // ignored unless advanceMode
};

struct AudioSettings {
    float volume = 1.0f;
    bool muted = false;
    int latencyMs = 64;  // target amount of buffered audio
    bool lowLatency = true;
};

struct SessionStats {
    double fps = 0;        // emulated frames per second (wall clock)
    double speed = 0;      // relative to native GBA speed
    uint32_t frameCounter = 0;
    size_t audioBufferedFrames = 0;
    uint64_t audioUnderrunFrames = 0;
    int audioSampleRate = 0;
    std::string audioBackend;
    bool fastForward = false;
    bool rewinding = false;
    double rewindSeconds = 0;
    size_t rewindBytes = 0;
    bool saveWriteFailed = false;
    bool videoUsesFallback = false;
};

/// A sprite captured for the Advance Studio asset dump.
struct DumpedSprite {
    uint64_t key = 0;
    int width = 0;
    int height = 0;
    std::vector<uint32_t> rgba;
};

class Session {
public:
    Session();
    ~Session();
    Session(const Session&) = delete;
    Session& operator=(const Session&) = delete;

    /// Loads the game. `output` may be null (timer-paced, silent).
    bool open(const LaunchParams& params, std::unique_ptr<audio::AudioOutput> output, std::string* error);
    /// Starts the emulation thread.
    void start();
    /// Stops the thread, writes pending saves and releases everything.
    void close();

    void setPaused(bool paused);
    bool paused() const { return paused_.load(); }

    void setKeys(uint32_t keys) { keys_.store(keys, std::memory_order_relaxed); }
    /// multiplier: 2..8, or 0 for "as fast as possible".
    void setFastForward(bool active, int multiplier);
    void setRewinding(bool active) { rewinding_.store(active); }
    void setRewindConfig(const engine::RewindConfig& config);
    void setAudioSettings(const AudioSettings& settings);
    void setFrameSkip(int frames);

    // --- Advance Engine controls (no-ops in Original mode) ---
    bool advanceMode() const { return advanceMode_; }
    bool setPatchEnabled(const std::string& id, bool enabled);
    std::vector<engine::PatchStatus> patchStatus() const;
    void setReplacementKeys(const std::vector<uint64_t>& keys);
    void setAssetDumpEnabled(bool enabled);
    std::vector<DumpedSprite> takeDumpedSprites();

    // --- Commands executed on the emulation thread (blocking) ---
    bool saveState(const std::string& path, std::string* error);
    bool loadState(const std::string& path, std::string* error);
    bool reset();
    /// Writes the cartridge save now if it changed; waits for the write.
    bool flushSave(std::string* error);

    /// Side-effect-free read of emulated memory (memory viewer / tests).
    bool readMemory(uint32_t address, size_t size, std::vector<uint8_t>* out);

    bool copyFrame(std::vector<uint32_t>* rgba);
    FrameMailbox& frames() { return mailbox_; }
    SessionStats stats() const;
    core::CartridgeHeader header() const { return header_; }
    uint32_t romCrc32() const { return romCrc32_; }

    /// Runs frames synchronously on the calling thread (tests / headless
    /// tools). Must not be used while the emulation thread is running.
    void runFramesForTest(int frames);

private:
    void threadMain();
    void runFrame();
    void paceFrame(std::chrono::steady_clock::time_point* next, double fps);
    void processCommands();
    void post(std::function<void()> fn);
    bool runOnEmuThread(std::function<void()> fn, int timeoutMs = 5000);
    void persistSaveIfDirty(bool force);
    void restartAudioIfNeeded();
    void detectAssets(std::vector<ReplacementDraw>* draws);

    // Core + engine (emulation thread only)
    std::unique_ptr<core::EmulatorCore> core_;
    engine::PatchEngine patches_;
    std::vector<engine::PatchStatus> romPatchStatus_;
    engine::RewindBuffer rewind_;
    engine::SpriteDetector detector_;
    std::vector<uint8_t> scratchState_;
    std::vector<uint8_t> lastPersistedSave_;
    std::vector<int16_t> audioScratch_;
    std::vector<ReplacementDraw> drawScratch_;
    std::vector<engine::SpriteInstance> spriteScratch_;
    std::chrono::steady_clock::time_point lastPublish_{};
    int audioStallFrames_ = 0;
    std::atomic<bool> saveRetryNeeded_{false};
    int framesSinceSnapshot_ = 0;
    uint32_t frameIndex_ = 0;

    // Configuration
    LaunchParams params_;
    bool advanceMode_ = false;
    core::CartridgeHeader header_;
    uint32_t romCrc32_ = 0;
    uint32_t romSize_ = 0;

    // Audio
    audio::AudioMixer mixer_;
    std::unique_ptr<audio::AudioOutput> output_;
    std::atomic<int> sampleRate_{48000};
    std::atomic<int> latencyFrames_{3072};
    bool audioRealtime_ = false;
    bool lowLatency_ = true;

    // Video
    FrameMailbox mailbox_;
    std::atomic<int> frameSkip_{0};

    // Control state
    std::atomic<uint32_t> keys_{0};
    std::atomic<bool> paused_{false};
    std::atomic<bool> fastForward_{false};
    std::atomic<int> ffMultiplier_{2};
    std::atomic<bool> rewinding_{false};
    std::atomic<bool> stop_{false};
    std::atomic<bool> running_{false};
    std::atomic<bool> saveWriteFailed_{false};

    // Asset replacement / dump
    mutable std::mutex assetMutex_;
    std::unordered_set<uint64_t> replacementKeys_;
    bool dumpEnabled_ = false;
    std::unordered_set<uint64_t> dumpedKeys_;
    std::vector<DumpedSprite> dumpQueue_;
    std::atomic<bool> assetsActive_{false};

    // Stats
    std::atomic<double> fps_{0};
    std::atomic<uint32_t> frameCounter_{0};
    std::atomic<double> rewindSeconds_{0};
    std::atomic<size_t> rewindBytes_{0};

    // Command queue
    std::mutex commandMutex_;
    std::condition_variable commandCv_;
    std::deque<std::function<void()>> commands_;
    std::thread thread_;

    std::unique_ptr<BackgroundWriter> writer_;
};

}  // namespace ax::runtime
