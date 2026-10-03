// SPDX-License-Identifier: MPL-2.0
// Advance Engine native tests: runtime patches, rewind history, sprite
// detection.
#include <cstring>
#include <random>

#include "ax/engine/patch_engine.h"
#include "ax/engine/rewind_buffer.h"
#include "ax/engine/sprite_detector.h"
#include "test_support.h"

using namespace ax::engine;
using namespace axtest;

namespace {
std::vector<uint8_t> bytes(const char* s) { return std::vector<uint8_t>(s, s + std::strlen(s)); }
}  // namespace

AX_TEST(rom_patch_applies_only_with_matching_original_bytes) {
    auto rom = loadTestRom();
    RomPatch good{"rom-on", 0x40B, bytes("ON!"), bytes("OFF")};
    RomPatch wrongRevision{"wrong-rev", 0x40B, bytes("XYZ"), bytes("ABC")};
    RomPatch outside{"outside", static_cast<uint32_t>(rom.size()) - 1, bytes("AB"), {}};
    RomPatch header{"header", 0xA0, bytes("HACK"), {}};
    auto status = applyRomPatches(&rom, {good, wrongRevision, outside, header});
    AX_EXPECT(status[0].state == PatchState::Applied);
    AX_EXPECT(std::memcmp(rom.data() + 0x400, "ROM PATCH: ON!", 14) == 0);
    AX_EXPECT(status[1].state == PatchState::Skipped);
    AX_EXPECT(status[2].state == PatchState::Failed);
    AX_EXPECT(status[3].state == PatchState::Failed);
    // Re-applying is detected as already present instead of failing.
    auto again = applyRomPatches(&rom, {good});
    AX_EXPECT(again[0].state == PatchState::Applied);
}

AX_TEST(memory_patch_validation_rejects_unsafe_targets) {
    MemoryPatch p;
    p.bytes = {1};
    p.address = 0x02000000;
    AX_EXPECT(validateMemoryPatch(p).empty());
    p.address = 0x04000000;  // I/O registers
    AX_EXPECT(!validateMemoryPatch(p).empty());
    p.address = 0x08000000;  // ROM: must use RomPatch
    AX_EXPECT(!validateMemoryPatch(p).empty());
    p.address = 0x0203FFFF;  // last EWRAM byte: fine
    AX_EXPECT(validateMemoryPatch(p).empty());
    p.bytes = {1, 2};  // crosses the end of EWRAM
    AX_EXPECT(!validateMemoryPatch(p).empty());
    p.bytes = {1};
    p.expect = {1, 2};
    AX_EXPECT(!validateMemoryPatch(p).empty());
}

AX_TEST(memory_patch_modifies_running_game) {
    auto core = bootTestRom();
    PatchEngine engine;
    MemoryPatch lives;
    lives.id = "infinite-lives";
    lives.address = 0x02000000;
    lives.bytes = {9, 0, 0, 0};
    MemoryPatch bad;
    bad.id = "bad";
    bad.address = 0x04000000;
    bad.bytes = {0};
    engine.setPatches({lives, bad});
    for (int i = 0; i < 400; ++i) {
        core->runFrame();
        engine.onFrame(core->memory());
    }
    AX_EXPECT_EQ(core->memory().read32(0x02000000), 9u);  // would be 1 without the patch
    auto status = engine.status();
    AX_EXPECT(status[0].state == PatchState::Active);
    AX_EXPECT(status[0].applyCount == 400);
    AX_EXPECT(status[1].state == PatchState::Failed);

    // Disabling hands control back to the game.
    engine.setEnabled("infinite-lives", false);
    for (int i = 0; i < 200; ++i) {
        core->runFrame();
        engine.onFrame(core->memory());
    }
    AX_EXPECT(core->memory().read32(0x02000000) < 9u);
}

AX_TEST(memory_patch_condition_and_expect_guards) {
    auto core = bootTestRom();
    core->runFrame();
    PatchEngine engine;
    MemoryPatch guarded;
    guarded.id = "guarded";
    guarded.address = 0x02000004;  // score
    guarded.bytes = {0, 0, 0, 0};
    guarded.mode = PatchMode::Once;
    guarded.condition = PatchCondition{CompareOp::Greater, 0x02000008, 4, 100};  // frame > 100
    MemoryPatch mismatched;
    mismatched.id = "mismatched";
    mismatched.address = 0x0200000C;  // magic "AXTS"
    mismatched.bytes = {1, 2, 3, 4};
    mismatched.expect = {0xDE, 0xAD, 0xBE, 0xEF};
    engine.setPatches({guarded, mismatched});
    for (int i = 0; i < 50; ++i) {
        core->runFrame();
        engine.onFrame(core->memory());
    }
    AX_EXPECT(engine.status()[0].state == PatchState::Pending);
    for (int i = 0; i < 100; ++i) {
        core->runFrame();
        engine.onFrame(core->memory());
    }
    AX_EXPECT(engine.status()[0].state == PatchState::Applied);
    AX_EXPECT_EQ(engine.status()[0].applyCount, 1u);
    AX_EXPECT(engine.status()[1].state == PatchState::Pending);
    AX_EXPECT_EQ(core->memory().read32(0x0200000C), 0x41585453u);  // untouched
}

AX_TEST(rewind_delta_codec_roundtrip) {
    std::mt19937 rng(42);
    std::vector<uint8_t> a(400000), b;
    for (auto& v : a) v = static_cast<uint8_t>(rng());
    b = a;
    for (int i = 0; i < 3000; ++i) b[rng() % b.size()] ^= static_cast<uint8_t>(rng() | 1);
    auto delta = RewindBuffer::encodeXor(b, a);
    AX_EXPECT(delta.size() < 20000);
    std::vector<uint8_t> restored = b;
    AX_EXPECT(RewindBuffer::decodeXor(delta, &restored));
    AX_EXPECT(restored == a);
    std::vector<uint8_t> same = a;
    AX_EXPECT(RewindBuffer::encodeXor(a, same).size() < 16);
}

AX_TEST(rewind_buffer_steps_back_through_real_states) {
    auto core = bootTestRom();
    RewindBuffer rewind;
    RewindConfig config;
    config.enabled = true;
    config.intervalFrames = 10;
    config.maxSeconds = 60;
    rewind.configure(config, core->framesPerSecond());
    std::vector<uint8_t> state;
    std::vector<uint32_t> frameAtSnapshot;
    for (int i = 1; i <= 300; ++i) {
        core->runFrame();
        if (i % 10 == 0) {
            core->saveState(&state);
            rewind.push(state);
            frameAtSnapshot.push_back(core->memory().read32(0x02000008));
        }
    }
    AX_EXPECT_EQ(rewind.count(), 30u);
    // Compressed history is much smaller than 30 full states.
    AX_EXPECT(rewind.memoryUsage() < 30 * core->stateSize() / 4);
    for (int k = 29; k >= 25; --k) {
        AX_EXPECT(rewind.pop(&state));
        AX_EXPECT(core->loadState(state.data(), state.size()));
        AX_EXPECT_EQ(core->memory().read32(0x02000008), frameAtSnapshot[static_cast<size_t>(k)]);
    }
}

AX_TEST(rewind_buffer_respects_limits) {
    RewindBuffer rewind;
    RewindConfig config;
    config.enabled = true;
    config.intervalFrames = 60;  // 1 snapshot per second
    config.maxSeconds = 5;
    rewind.configure(config, 60.0);
    std::vector<uint8_t> state(1000, 0);
    for (int i = 0; i < 20; ++i) {
        state[static_cast<size_t>(i)] = static_cast<uint8_t>(i + 1);
        rewind.push(state);
    }
    AX_EXPECT(rewind.count() <= 6u);
    config.enabled = false;
    rewind.configure(config, 60.0);
    AX_EXPECT_EQ(rewind.count(), 0u);
}

AX_TEST(sprite_detector_fingerprints_and_decodes_the_test_sprite) {
    auto core = bootTestRom();
    runFrames(*core, 30);
    SpriteDetector detector;
    std::vector<SpriteInstance> sprites;
    detector.scan(core->memory(), &sprites);
    AX_EXPECT_EQ(sprites.size(), 1u);
    const SpriteInstance& s = sprites[0];
    AX_EXPECT_EQ(s.width, 16);
    AX_EXPECT_EQ(s.height, 16);
    AX_EXPECT_EQ(s.x, 112);
    AX_EXPECT_EQ(s.y, 96);
    std::vector<uint32_t> rgba;
    AX_EXPECT(detector.decode(core->memory(), s, &rgba));
    AX_EXPECT_EQ(rgba.size(), 256u);
    AX_EXPECT_EQ(rgba[0], 0u);                                // transparent corner
    AX_EXPECT_EQ(rgba[8 * 16 + 8] & 0x00FFFFFFu, rgb15ToFrame(26, 31, 31));  // core colour
    uint64_t keyBefore = replacementKey(s);

    // Moving keeps the fingerprint; recolouring (A) changes the palette part.
    runFrames(*core, 5, ax::core::kKeyRight);
    detector.scan(core->memory(), &sprites);
    AX_EXPECT(replacementKey(sprites[0]) == keyBefore);
    AX_EXPECT(sprites[0].x > 112);
    runFrames(*core, 2, ax::core::kKeyA);
    runFrames(*core, 1);
    detector.scan(core->memory(), &sprites);
    AX_EXPECT(sprites[0].tileHash == s.tileHash);
    AX_EXPECT(replacementKey(sprites[0]) != keyBefore);

    uint64_t parsed = 0;
    AX_EXPECT(hexToHash(hashToHex(keyBefore), &parsed));
    AX_EXPECT(parsed == keyBefore);
}
