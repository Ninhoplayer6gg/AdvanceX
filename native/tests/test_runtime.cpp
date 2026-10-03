// SPDX-License-Identifier: MPL-2.0
// Runtime/session tests: cartridge saves on disk, save-state files, pacing,
// fast-forward, rewind, Advance Mode gating of patches and asset detection.
#include <unistd.h>

#include <chrono>
#include <cstring>
#include <thread>

#include "ax/common/file_io.h"
#include "ax/runtime/session.h"
#include "ax/runtime/state_file.h"
#include "test_support.h"

using namespace ax::runtime;
using namespace axtest;

namespace {

std::string tempDir(const char* name) {
    std::string dir = (outputDir().empty() ? std::string("/tmp") : outputDir()) + "/runtime_" + name + "_" +
                      std::to_string(getpid());
    ax::fileio::makeDirs(dir);
    return dir;
}

std::string testRomPath() { return assetsDir() + "/advancex-testcart.gba"; }

LaunchParams params(const std::string& dir, bool advance = false) {
    LaunchParams p;
    p.romPath = testRomPath();
    p.savePath = dir + "/game.sav";
    p.gameId = "ZAXT-test";
    p.advanceMode = advance;
    return p;
}

uint32_t read32(Session& s, uint32_t address) {
    std::vector<uint8_t> b;
    if (!s.readMemory(address, 4, &b)) throw Failure{"readMemory failed"};
    return static_cast<uint32_t>(b[0] | (b[1] << 8) | (b[2] << 16) | (static_cast<uint32_t>(b[3]) << 24));
}

std::vector<uint8_t> text(const char* s) { return std::vector<uint8_t>(s, s + std::strlen(s)); }

}  // namespace

AX_TEST(session_writes_cartridge_save_atomically_with_backup) {
    std::string dir = tempDir("save");
    std::vector<uint8_t> file;
    {
        Session s;
        std::string err;
        AX_EXPECT(s.open(params(dir), nullptr, &err));
        s.runFramesForTest(240);
        AX_EXPECT(s.flushSave(&err));
        s.close();
    }
    AX_EXPECT(ax::fileio::readFile(dir + "/game.sav", &file));
    AX_EXPECT(file.size() >= 32768);
    AX_EXPECT(std::memcmp(file.data(), "AXSV", 4) == 0);
    AX_EXPECT_EQ(file[4], 1);
    AX_EXPECT(!ax::fileio::fileExists(dir + "/game.sav.tmp"));
    {
        Session s;
        std::string err;
        AX_EXPECT(s.open(params(dir), nullptr, &err));
        s.runFramesForTest(240);
        s.close();  // close() must persist without an explicit flush
    }
    AX_EXPECT(ax::fileio::readFile(dir + "/game.sav", &file));
    AX_EXPECT_EQ(file[4], 2);
    std::vector<uint8_t> backup;
    AX_EXPECT(ax::fileio::readFile(dir + "/game.sav.bak", &backup));
    AX_EXPECT_EQ(backup[4], 1);  // previous generation kept

    // A corrupted primary save falls back to the backup instead of losing it.
    ax::fileio::writeFileAtomic(dir + "/game.sav", "", 0, false);
    {
        Session s;
        std::string err;
        AX_EXPECT(s.open(params(dir), nullptr, &err));
        s.runFramesForTest(240);
        s.close();
    }
    AX_EXPECT(ax::fileio::readFile(dir + "/game.sav", &file));
    AX_EXPECT_EQ(file[4], 2);
}

AX_TEST(session_state_files_roundtrip_and_reject_foreign_games) {
    std::string dir = tempDir("states");
    Session s;
    std::string err;
    AX_EXPECT(s.open(params(dir), nullptr, &err));
    s.runFramesForTest(100);
    AX_EXPECT(s.saveState(dir + "/slot1.axstate", &err));
    uint32_t frameAtSave = read32(s, 0x02000008);
    s.runFramesForTest(100);
    AX_EXPECT(read32(s, 0x02000008) > frameAtSave);
    AX_EXPECT(s.loadState(dir + "/slot1.axstate", &err));
    AX_EXPECT_EQ(read32(s, 0x02000008), frameAtSave);

    // Tamper with the ROM CRC field: must be refused, game untouched.
    std::vector<uint8_t> file;
    AX_EXPECT(ax::fileio::readFile(dir + "/slot1.axstate", &file));
    std::vector<uint8_t> state;
    StateFileInfo info;
    AX_EXPECT(decodeStateFile(file, &state, &info, &err));
    AX_EXPECT(info.coreId == "mgba");
    info.romCrc32 ^= 1;
    AX_EXPECT(writeStateFile(dir + "/foreign.axstate", state, info, &err));
    s.runFramesForTest(10);
    uint32_t before = read32(s, 0x02000008);
    AX_EXPECT(!s.loadState(dir + "/foreign.axstate", &err));
    AX_EXPECT(err.find("different game") != std::string::npos);
    AX_EXPECT_EQ(read32(s, 0x02000008), before);

    // Truncated file.
    file.resize(file.size() / 2);
    ax::fileio::writeFileAtomic(dir + "/broken.axstate", file.data(), file.size(), false);
    AX_EXPECT(!s.loadState(dir + "/broken.axstate", &err));
    s.close();
}

AX_TEST(session_thread_paces_at_native_speed_and_fast_forwards) {
    std::string dir = tempDir("pace");
    Session s;
    std::string err;
    AX_EXPECT(s.open(params(dir), nullptr, &err));  // null audio -> timer pacing
    s.start();
    std::this_thread::sleep_for(std::chrono::milliseconds(1000));
    uint32_t normal = s.stats().frameCounter;
    AX_EXPECT(normal > 50 && normal < 70);

    s.setFastForward(true, 0);  // unlimited
    std::this_thread::sleep_for(std::chrono::milliseconds(500));
    uint32_t fast = s.stats().frameCounter - normal;
    AX_EXPECT(fast > 150);  // >10x on any reasonable machine
    s.setFastForward(true, 3);
    uint32_t mark = s.stats().frameCounter;
    std::this_thread::sleep_for(std::chrono::milliseconds(1000));
    uint32_t triple = s.stats().frameCounter - mark;
    AX_EXPECT(triple > 160 && triple < 200);
    s.setFastForward(false, 0);

    s.setPaused(true);
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    uint32_t pausedAt = s.stats().frameCounter;
    std::this_thread::sleep_for(std::chrono::milliseconds(300));
    AX_EXPECT_EQ(s.stats().frameCounter, pausedAt);
    // Commands still work while paused.
    AX_EXPECT(s.saveState(dir + "/paused.axstate", &err));
    s.setPaused(false);
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    AX_EXPECT(s.stats().frameCounter > pausedAt);
    s.close();
}

AX_TEST(session_rewind_goes_back_in_time) {
    std::string dir = tempDir("rewind");
    Session s;
    std::string err;
    AX_EXPECT(s.open(params(dir), nullptr, &err));
    ax::engine::RewindConfig config;
    config.enabled = true;
    config.intervalFrames = 5;
    config.maxSeconds = 20;
    s.setRewindConfig(config);
    s.start();
    s.setFastForward(true, 4);
    std::this_thread::sleep_for(std::chrono::milliseconds(1000));
    s.setFastForward(false, 0);
    uint32_t before = read32(s, 0x02000008);
    AX_EXPECT(before > 150);
    AX_EXPECT(s.stats().rewindSeconds > 2.0);
    s.setRewinding(true);
    std::this_thread::sleep_for(std::chrono::milliseconds(400));
    s.setRewinding(false);
    uint32_t after = read32(s, 0x02000008);
    AX_EXPECT(after + 60 < before);
    s.close();
}

AX_TEST(session_applies_patches_only_in_advance_mode) {
    std::string dir = tempDir("patches");
    ax::engine::MemoryPatch lives;
    lives.id = "lives";
    lives.address = 0x02000000;
    lives.bytes = {9, 0, 0, 0};
    ax::engine::RomPatch romText{"rom-text", 0x40B, text("ON!"), text("OFF")};

    for (bool advance : {false, true}) {
        LaunchParams p = params(dir, advance);
        p.memoryPatches = {lives};
        p.romPatches = {romText};
        Session s;
        std::string err;
        AX_EXPECT(s.open(p, nullptr, &err));
        s.runFramesForTest(400);
        std::vector<uint8_t> romBytes;
        AX_EXPECT(s.readMemory(0x08000400, 14, &romBytes));
        std::string romString(romBytes.begin(), romBytes.end());
        if (advance) {
            AX_EXPECT_EQ(read32(s, 0x02000000), 9u);
            AX_EXPECT(romString == "ROM PATCH: ON!");
            AX_EXPECT_EQ(s.patchStatus().size(), 2u);
        } else {
            AX_EXPECT_EQ(read32(s, 0x02000000), 1u);
            AX_EXPECT(romString == "ROM PATCH: OFF");
            AX_EXPECT_EQ(s.patchStatus().size(), 0u);
        }
        s.close();
    }
    // The ROM file itself is never modified.
    auto rom = loadTestRom();
    AX_EXPECT(std::memcmp(rom.data() + 0x400, "ROM PATCH: OFF", 14) == 0);
}

AX_TEST(session_asset_dump_and_replacement_overlays) {
    std::string dir = tempDir("assets");
    Session s;
    std::string err;
    AX_EXPECT(s.open(params(dir, true), nullptr, &err));
    s.setAssetDumpEnabled(true);
    s.runFramesForTest(30);
    auto all = s.takeDumpedSprites();
    // Frame 1 still has the power-on OAM (blank 8x8 sprites) before the game
    // hides them, so two unique assets are expected: that one and the crystal.
    AX_EXPECT_EQ(all.size(), 2u);
    std::vector<DumpedSprite> dumped;
    for (auto& d : all) {
        if (d.width == 16) dumped.push_back(d);
    }
    AX_EXPECT_EQ(dumped.size(), 1u);
    AX_EXPECT_EQ(dumped[0].height, 16);
    AX_EXPECT_EQ(dumped[0].rgba.size(), 256u);
    s.setAssetDumpEnabled(false);
    AX_EXPECT(s.takeDumpedSprites().empty());

    s.setReplacementKeys({dumped[0].key});
    s.runFramesForTest(1);
    FramePacket packet;
    AX_EXPECT(s.frames().fetchIfNewer(&packet));
    AX_EXPECT_EQ(packet.overlays.size(), 1u);
    AX_EXPECT(packet.overlays[0].key == dumped[0].key);
    AX_EXPECT(packet.overlays[0].x == 112.0f);
    AX_EXPECT(packet.overlays[0].width == 16.0f);
    s.close();

    // Original mode never runs asset detection.
    Session original;
    AX_EXPECT(original.open(params(dir, false), nullptr, &err));
    original.setReplacementKeys({dumped[0].key});
    original.setAssetDumpEnabled(true);
    original.runFramesForTest(30);
    AX_EXPECT(original.takeDumpedSprites().empty());
    FramePacket p2;
    AX_EXPECT(original.frames().fetchIfNewer(&p2));
    AX_EXPECT(p2.overlays.empty());
    original.close();
}

AX_TEST(session_open_reports_missing_rom) {
    Session s;
    LaunchParams p;
    p.romPath = "/nonexistent/game.gba";
    std::string err;
    AX_EXPECT(!s.open(p, nullptr, &err));
    AX_EXPECT(err.find("Cannot read the ROM") != std::string::npos);
}
