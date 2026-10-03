// SPDX-License-Identifier: MPL-2.0
// Integration tests for the mGBA-backed EmulatorCore, driven by the AdvanceX
// test cartridge (tools/testrom).
#include <cmath>
#include <cstring>

#include "test_support.h"

using namespace ax::core;
using namespace axtest;

namespace {
// Colours used by the test cartridge (see tools/testrom/src/main.c).
constexpr uint32_t kTextWhite = rgb15ToFrame(31, 31, 31);
constexpr uint32_t kSpriteCoreViolet = rgb15ToFrame(26, 31, 31);
constexpr uint32_t kSpriteCoreGold = rgb15ToFrame(31, 31, 20);
constexpr uint32_t kGameStateAddr = 0x02000000;
}  // namespace

AX_TEST(header_is_parsed_from_rom) {
    auto rom = loadTestRom();
    CartridgeHeader header;
    AX_EXPECT(parseCartridgeHeader(rom.data(), rom.size(), &header));
    AX_EXPECT(header.title == "ADVANCEXTEST");
    AX_EXPECT(header.gameCode == "ZAXT");
    AX_EXPECT(header.makerCode == "AX");
    AX_EXPECT(header.checksumValid);
}

AX_TEST(rejects_invalid_rom) {
    auto core = createMgbaCore();
    LoadRequest request;
    request.rom.assign(16, 0);
    std::string error;
    AX_EXPECT(!core->load(std::move(request), &error));
    AX_EXPECT(!error.empty());
    AX_EXPECT(!core->isLoaded());
}

AX_TEST(boots_and_renders_text) {
    auto core = bootTestRom();
    AX_EXPECT(core->isLoaded());
    AX_EXPECT(std::fabs(core->framesPerSecond() - 59.7275) < 0.01);
    runFrames(*core, 60);
    AX_EXPECT(core->frameCounter() >= 60);
    // Title + labels are drawn in white; the sprite core is light cyan.
    AX_EXPECT(findColor(*core, kTextWhite) > 500);
    AX_EXPECT(findColor(*core, kSpriteCoreViolet) > 20);
    // The game keeps its own frame counter in EWRAM.
    AX_EXPECT(core->memory().read32(kGameStateAddr + 8) >= 58);
    AX_EXPECT_EQ(core->memory().read32(kGameStateAddr + 12), 0x41585453u);
}

AX_TEST(dpad_moves_sprite_and_a_recolours_it) {
    auto core = bootTestRom();
    runFrames(*core, 30);
    double x0 = 0, y0 = 0;
    AX_EXPECT(findColor(*core, kSpriteCoreViolet, &x0, &y0) > 0);
    runFrames(*core, 10, kKeyRight | kKeyDown);
    double x1 = 0, y1 = 0;
    AX_EXPECT(findColor(*core, kSpriteCoreViolet, &x1, &y1) > 0);
    AX_EXPECT(x1 - x0 > 15.0);
    AX_EXPECT(y1 - y0 > 15.0);

    runFrames(*core, 2, kKeyA);
    runFrames(*core, 2, 0);
    AX_EXPECT(findColor(*core, kSpriteCoreGold) > 20);
    AX_EXPECT_EQ(findColor(*core, kSpriteCoreViolet), 0);
}

AX_TEST(produces_audio) {
    auto core = bootTestRom();
    core->setAudioSampleRate(48000.0);
    std::vector<int16_t> buffer(4096 * 2);
    size_t frames = 0;
    long long energy = 0;
    for (int i = 0; i < 60; ++i) {
        core->runFrame();
        size_t n = core->drainAudio(buffer.data(), 4096);
        frames += n;
        for (size_t s = 0; s < n * 2; ++s) energy += std::abs(buffer[s]);
    }
    // ~48000 frames per second of emulated time (60 frames ≈ 1.0046 s).
    AX_EXPECT(frames > 47000 && frames < 49500);
    AX_EXPECT(energy > 1000000);  // the arpeggio is audible
}

AX_TEST(cartridge_save_persists_across_boots) {
    std::vector<uint8_t> save;
    {
        auto core = bootTestRom();
        bool dirty = false;
        for (int i = 0; i < 240 && !dirty; ++i) {
            core->runFrame();
            dirty = core->consumeSaveDirty();
        }
        AX_EXPECT(dirty);
        save = core->saveData();
    }
    AX_EXPECT(save.size() >= 32768);
    AX_EXPECT(std::memcmp(save.data(), "AXSV", 4) == 0);
    AX_EXPECT_EQ(save[4], 1);

    auto core = bootTestRom(save);
    for (int i = 0; i < 240; ++i) {
        core->runFrame();
        if (core->consumeSaveDirty()) break;
    }
    auto save2 = core->saveData();
    AX_EXPECT(save2.size() == save.size());
    AX_EXPECT_EQ(save2[4], 2);
}

AX_TEST(save_state_roundtrip_is_deterministic) {
    auto core = bootTestRom();
    runFrames(*core, 45, kKeyRight);
    std::vector<uint8_t> state;
    AX_EXPECT(core->saveState(&state));
    AX_EXPECT(state.size() == core->stateSize());

    runFrames(*core, 90, kKeyUp | kKeyA);
    uint64_t expected = frameHash(*core);
    uint32_t expectedScore = core->memory().read32(kGameStateAddr + 4);

    runFrames(*core, 30, kKeyLeft);  // diverge
    AX_EXPECT(core->loadState(state.data(), state.size()));
    runFrames(*core, 90, kKeyUp | kKeyA);
    AX_EXPECT(frameHash(*core) == expected);
    AX_EXPECT_EQ(core->memory().read32(kGameStateAddr + 4), expectedScore);

    // Corrupt/short states are rejected without touching the running game.
    AX_EXPECT(!core->loadState(state.data(), state.size() / 2));
}

AX_TEST(memory_bus_reads_and_writes_without_side_effects) {
    auto core = bootTestRom();
    runFrames(*core, 5);
    auto& bus = core->memory();
    AX_EXPECT_EQ(bus.read32(kGameStateAddr), 3u);  // lives
    bus.write32(kGameStateAddr, 7);
    AX_EXPECT_EQ(bus.read32(kGameStateAddr), 7u);
    size_t size = 0;
    const uint8_t* oam = bus.regionData(MemoryRegion::Oam, &size);
    AX_EXPECT(oam != nullptr);
    AX_EXPECT_EQ(size, 1024u);
    const uint8_t* rom = bus.regionData(MemoryRegion::Rom, &size);
    AX_EXPECT(rom != nullptr);
    AX_EXPECT(std::memcmp(rom + 0x400, "ROM PATCH: OFF", 14) == 0);
    AX_EXPECT(classifyAddress(0x02000010) == MemoryRegion::Ewram);
    AX_EXPECT(classifyAddress(0x0E000000) == MemoryRegion::Save);
    AX_EXPECT(classifyAddress(0x10000000) == MemoryRegion::Invalid);
}
