// SPDX-License-Identifier: MPL-2.0
// EmulatorCore implementation backed by mGBA (https://mgba.io, MPL-2.0).
//
// This is the only translation unit in AdvanceX that includes mGBA headers.
#include <algorithm>
#include <atomic>
#include <cstring>
#include <memory>
#include <mutex>

#include <mgba-util/vfs.h>
#include <mgba/core/blip_buf.h>
#include <mgba/core/config.h>
#include <mgba/core/core.h>
#include <mgba/core/log.h>
#include <mgba/core/version.h>

#include "ax/common/log.h"
#include "ax/core/emulator_core.h"

namespace ax::core {
namespace {

constexpr const char* kTag = "MgbaCore";

// ---------------------------------------------------------------------------
// mGBA log bridge
// ---------------------------------------------------------------------------

struct LogBridge {
    mLogger logger{};
};

void bridgeLog(mLogger*, int category, enum mLogLevel level, const char* format, va_list args) {
    ax::log::Level axLevel;
    switch (level) {
        case mLOG_FATAL:
        case mLOG_ERROR:
            axLevel = ax::log::Level::Error;
            break;
        case mLOG_WARN:
            axLevel = ax::log::Level::Warn;
            break;
        case mLOG_INFO:
            axLevel = ax::log::Level::Info;
            break;
        default:
            // STUB / DEBUG / GAME_ERROR are very chatty during normal play.
            return;
    }
    char tag[48];
    std::snprintf(tag, sizeof(tag), "mgba/%s", mLogCategoryId(category));
    ax::log::writeV(axLevel, tag, format, args);
}

void installLogBridgeOnce() {
    static std::once_flag once;
    std::call_once(once, [] {
        static LogBridge bridge;
        bridge.logger.log = bridgeLog;
        bridge.logger.filter = nullptr;
        mLogSetDefaultLogger(&bridge.logger);
    });
}

// ---------------------------------------------------------------------------
// Save-data VFile
//
// mGBA writes cartridge saves through a VFile and calls sync() once the game
// has stopped writing for a short while. We keep the data in memory (a
// VFileMemChunk) and only raise a flag on sync(); the runtime then persists a
// snapshot with an atomic write. The byte layout is exactly what mGBA itself
// would write to a .sav file (including the RTC trailer), so saves remain
// interchangeable with desktop mGBA.
// ---------------------------------------------------------------------------

struct SaveVFile {
    VFile d;  // must be first: mGBA only sees a VFile*
    VFile* inner;
    std::shared_ptr<std::atomic<bool>> dirty;
};

SaveVFile* asSave(VFile* vf) { return reinterpret_cast<SaveVFile*>(vf); }

bool saveClose(VFile* vf) {
    SaveVFile* s = asSave(vf);
    s->inner->close(s->inner);
    delete s;
    return true;
}
off_t saveSeek(VFile* vf, off_t offset, int whence) { return asSave(vf)->inner->seek(asSave(vf)->inner, offset, whence); }
ssize_t saveRead(VFile* vf, void* buffer, size_t size) { return asSave(vf)->inner->read(asSave(vf)->inner, buffer, size); }
ssize_t saveReadline(VFile* vf, char* buffer, size_t size) {
    return asSave(vf)->inner->readline(asSave(vf)->inner, buffer, size);
}
ssize_t saveWrite(VFile* vf, const void* buffer, size_t size) {
    return asSave(vf)->inner->write(asSave(vf)->inner, buffer, size);
}
void* saveMap(VFile* vf, size_t size, int flags) { return asSave(vf)->inner->map(asSave(vf)->inner, size, flags); }
void saveUnmap(VFile* vf, void* memory, size_t size) { asSave(vf)->inner->unmap(asSave(vf)->inner, memory, size); }
void saveTruncate(VFile* vf, size_t size) { asSave(vf)->inner->truncate(asSave(vf)->inner, size); }
ssize_t saveSize(VFile* vf) { return asSave(vf)->inner->size(asSave(vf)->inner); }
bool saveSync(VFile* vf, void* buffer, size_t size) {
    SaveVFile* s = asSave(vf);
    bool ok = s->inner->sync(s->inner, buffer, size);
    s->dirty->store(true, std::memory_order_release);
    return ok;
}

SaveVFile* createSaveVFile(const std::vector<uint8_t>& initial, std::shared_ptr<std::atomic<bool>> dirty) {
    VFile* inner = VFileMemChunk(initial.empty() ? nullptr : initial.data(), initial.size());
    if (!inner) {
        return nullptr;
    }
    auto* s = new SaveVFile{};
    s->d.close = saveClose;
    s->d.seek = saveSeek;
    s->d.read = saveRead;
    s->d.readline = saveReadline;
    s->d.write = saveWrite;
    s->d.map = saveMap;
    s->d.unmap = saveUnmap;
    s->d.truncate = saveTruncate;
    s->d.size = saveSize;
    s->d.sync = saveSync;
    s->inner = inner;
    s->dirty = std::move(dirty);
    return s;
}

// ---------------------------------------------------------------------------
// Memory bus
// ---------------------------------------------------------------------------

class MgbaMemoryBus final : public MemoryBus {
public:
    void attach(mCore* core, uint32_t romSize) {
        core_ = core;
        romSize_ = romSize;
    }
    void detach() {
        core_ = nullptr;
        romSize_ = 0;
    }

    uint8_t read8(uint32_t address) override { return core_ ? static_cast<uint8_t>(core_->rawRead8(core_, address, -1)) : 0; }
    uint16_t read16(uint32_t address) override {
        return core_ ? static_cast<uint16_t>(core_->rawRead16(core_, address, -1)) : 0;
    }
    uint32_t read32(uint32_t address) override { return core_ ? core_->rawRead32(core_, address, -1) : 0; }
    void write8(uint32_t address, uint8_t value) override {
        if (core_) core_->rawWrite8(core_, address, -1, value);
    }
    void write16(uint32_t address, uint16_t value) override {
        if (core_) core_->rawWrite16(core_, address, -1, value);
    }
    void write32(uint32_t address, uint32_t value) override {
        if (core_) core_->rawWrite32(core_, address, -1, value);
    }

    const uint8_t* regionData(MemoryRegion region, size_t* size) override {
        if (!core_ || region == MemoryRegion::Invalid || region == MemoryRegion::Count) {
            if (size) *size = 0;
            return nullptr;
        }
        size_t blockSize = 0;
        void* block = mCoreGetMemoryBlock(core_, regionInfo(region).base, &blockSize);
        if (size) *size = block ? blockSize : 0;
        return static_cast<const uint8_t*>(block);
    }

    uint32_t romSize() const override { return romSize_; }

private:
    mCore* core_ = nullptr;
    uint32_t romSize_ = 0;
};

// ---------------------------------------------------------------------------
// Core
// ---------------------------------------------------------------------------

class MgbaCore final : public EmulatorCore {
public:
    MgbaCore() {
        installLogBridgeOnce();
        video_.assign(static_cast<size_t>(kGbaWidth) * kGbaHeight, 0xFF000000u);
    }
    ~MgbaCore() override { destroy(); }

    const char* id() const override { return "mgba"; }
    const char* version() const override { return projectVersion; }

    bool load(LoadRequest request, std::string* error) override {
        destroy();
        if (request.rom.size() < 0xC0) {
            if (error) *error = "ROM image is too small to be a GBA cartridge";
            return false;
        }
        if (request.rom.size() > 0x02000000u) {
            if (error) *error = "ROM image is larger than 32 MiB";
            return false;
        }

        rom_ = std::move(request.rom);
        bios_ = std::move(request.bios);
        parseCartridgeHeader(rom_.data(), rom_.size(), &header_);

        core_ = mCoreCreate(mPLATFORM_GBA);
        if (!core_) {
            if (error) *error = "mGBA could not create a GBA core";
            return false;
        }
        mCoreInitConfig(core_, nullptr);
        if (!core_->init(core_)) {
            if (error) *error = "mGBA core failed to initialize";
            mCoreConfigDeinit(&core_->config);
            core_ = nullptr;
            return false;
        }

        // Defaults chosen for handheld battery life without hurting accuracy:
        // idle-loop detection skips busy-wait loops the game would spin in.
        mCoreConfigSetDefaultIntValue(&core_->config, "skipBios", request.skipBiosIntro ? 1 : 0);
        mCoreConfigSetDefaultIntValue(&core_->config, "useBios", 0);
        mCoreConfigSetDefaultIntValue(&core_->config, "frameskip", 0);
        mCoreConfigSetDefaultValue(&core_->config, "idleOptimization", "detect");
        // Unity gain inside the core; user volume/mute is applied by the
        // AdvanceX audio output so it never alters emulation state.
        mCoreConfigSetDefaultIntValue(&core_->config, "volume", 0x100);
        mCoreConfigSetDefaultIntValue(&core_->config, "mute", 0);
        mCoreLoadForeignConfig(core_, &core_->config);

        core_->setVideoBuffer(core_, reinterpret_cast<color_t*>(video_.data()), kGbaWidth);
        core_->setAudioBufferSize(core_, 2048);
        applyAudioRate();

        VFile* romVf = VFileFromMemory(rom_.data(), rom_.size());
        if (!romVf || !core_->loadROM(core_, romVf)) {
            if (romVf) romVf->close(romVf);
            if (error) *error = "mGBA rejected the ROM image";
            destroy();
            return false;
        }

        saveDirty_ = std::make_shared<std::atomic<bool>>(false);
        SaveVFile* saveVf = createSaveVFile(request.saveData, saveDirty_);
        if (!saveVf) {
            if (error) *error = "Out of memory while preparing save data";
            destroy();
            return false;
        }
        saveVf_ = saveVf;
        if (!core_->loadSave(core_, &saveVf->d)) {
            // mGBA did not take ownership.
            saveVf->d.close(&saveVf->d);
            saveVf_ = nullptr;
            AX_LOGW(kTag, "Core refused the save file; continuing without cartridge save");
        }

        if (!bios_.empty()) {
            VFile* biosVf = VFileFromMemory(bios_.data(), bios_.size());
            if (!biosVf || !core_->loadBIOS(core_, biosVf, 0)) {
                if (biosVf) biosVf->close(biosVf);
                AX_LOGW(kTag, "BIOS image rejected; using built-in HLE BIOS");
                bios_.clear();
            }
        }

        core_->reset(core_);
        bus_.attach(core_, static_cast<uint32_t>(rom_.size()));
        AX_LOGI(kTag, "Loaded '%s' (%s) %zu bytes, BIOS: %s", header_.title.c_str(), header_.gameCode.c_str(),
                rom_.size(), bios_.empty() ? "HLE" : "user");
        return true;
    }

    void unload() override { destroy(); }
    bool isLoaded() const override { return core_ != nullptr; }

    void reset() override {
        if (core_) core_->reset(core_);
    }

    void runFrame() override {
        if (core_) core_->runFrame(core_);
    }

    void setKeys(uint32_t keys) override {
        if (core_) core_->setKeys(core_, keys & kKeyMask);
    }

    FrameView frame() const override {
        return FrameView{video_.data(), kGbaWidth, kGbaHeight, kGbaWidth};
    }

    double framesPerSecond() const override {
        if (!core_) return 59.7275;
        return static_cast<double>(core_->frequency(core_)) / static_cast<double>(core_->frameCycles(core_));
    }

    uint32_t frameCounter() const override { return core_ ? core_->frameCounter(core_) : 0; }

    void setFrameSkip(int frames) override {
        frames = std::clamp(frames, 0, 3);
        if (!core_) return;
        mCoreConfigSetIntValue(&core_->config, "frameskip", frames);
        core_->reloadConfigOption(core_, "frameskip", &core_->config);
    }

    void setAudioSampleRate(double hz) override {
        sampleRate_ = std::clamp(hz, 8000.0, 192000.0);
        applyAudioRate();
    }

    size_t drainAudio(int16_t* interleaved, size_t maxFrames) override {
        if (!core_) return 0;
        blip_t* left = core_->getAudioChannel(core_, 0);
        blip_t* right = core_->getAudioChannel(core_, 1);
        int available = blip_samples_avail(left);
        int count = static_cast<int>(std::min<size_t>(static_cast<size_t>(std::max(available, 0)), maxFrames));
        if (count <= 0) return 0;
        int produced = blip_read_samples(left, interleaved, count, 1);
        blip_read_samples(right, interleaved + 1, count, 1);
        return static_cast<size_t>(produced);
    }

    size_t stateSize() override { return core_ ? core_->stateSize(core_) : 0; }

    bool saveState(std::vector<uint8_t>* out) override {
        if (!core_ || !out) return false;
        out->resize(core_->stateSize(core_));
        return core_->saveState(core_, out->data());
    }

    bool loadState(const uint8_t* data, size_t size) override {
        if (!core_ || !data || size != core_->stateSize(core_)) return false;
        return core_->loadState(core_, data);
    }

    bool consumeSaveDirty() override {
        return saveDirty_ && saveDirty_->exchange(false, std::memory_order_acq_rel);
    }

    std::vector<uint8_t> saveData() override {
        std::vector<uint8_t> out;
        if (!core_ || !saveVf_) return out;
        VFile* inner = saveVf_->inner;
        ssize_t size = inner->size(inner);
        if (size <= 0) return out;
        out.resize(static_cast<size_t>(size));
        off_t previous = inner->seek(inner, 0, SEEK_CUR);
        inner->seek(inner, 0, SEEK_SET);
        ssize_t read = inner->read(inner, out.data(), out.size());
        inner->seek(inner, previous, SEEK_SET);
        if (read != size) out.clear();
        return out;
    }

    CartridgeHeader header() const override { return header_; }
    MemoryBus& memory() override { return bus_; }

private:
    void applyAudioRate() {
        if (!core_) return;
        double clock = static_cast<double>(core_->frequency(core_));
        blip_set_rates(core_->getAudioChannel(core_, 0), clock, sampleRate_);
        blip_set_rates(core_->getAudioChannel(core_, 1), clock, sampleRate_);
    }

    void destroy() {
        bus_.detach();
        if (core_) {
            mCoreConfigDeinit(&core_->config);
            core_->deinit(core_);  // closes ROM, BIOS and save VFiles
            core_ = nullptr;
        }
        saveVf_ = nullptr;
        saveDirty_.reset();
        rom_.clear();
        rom_.shrink_to_fit();
        bios_.clear();
        header_ = CartridgeHeader{};
    }

    mCore* core_ = nullptr;
    std::vector<uint32_t> video_;
    std::vector<uint8_t> rom_;
    std::vector<uint8_t> bios_;
    SaveVFile* saveVf_ = nullptr;  // owned by mGBA once loaded
    std::shared_ptr<std::atomic<bool>> saveDirty_;
    CartridgeHeader header_;
    MgbaMemoryBus bus_;
    double sampleRate_ = 48000.0;
};

}  // namespace

std::unique_ptr<EmulatorCore> createMgbaCore() { return std::make_unique<MgbaCore>(); }

}  // namespace ax::core
