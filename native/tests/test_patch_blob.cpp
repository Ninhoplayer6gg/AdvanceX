// SPDX-License-Identifier: MPL-2.0
#include <cstdio>

#include "ax/runtime/patch_blob.h"
#include "test_support.h"

using namespace ax::runtime;
using namespace ax::engine;

namespace {

PatchSet sampleSet() {
    PatchSet set;
    set.rom.push_back(RomPatch{"rom", 0x40B, {'O', 'N', '!'}, {'O', 'F', 'F'}});
    MemoryPatch m;
    m.id = "lives";
    m.address = 0x02000000;
    m.bytes = {9, 0, 0, 0};
    m.mode = PatchMode::EveryFrame;
    m.condition = PatchCondition{CompareOp::Greater, 0x02000008, 4, 100};
    m.enabled = true;
    set.memory.push_back(m);
    return set;
}

std::string toHex(const std::vector<uint8_t>& v) {
    std::string s;
    char b[3];
    for (uint8_t x : v) {
        std::snprintf(b, sizeof(b), "%02x", x);
        s += b;
    }
    return s;
}

}  // namespace

AX_TEST(patch_blob_roundtrip) {
    PatchSet set = sampleSet();
    auto blob = encodePatchBlob(set);
    PatchSet decoded;
    std::string err;
    AX_EXPECT(decodePatchBlob(blob.data(), blob.size(), &decoded, &err));
    AX_EXPECT_EQ(decoded.rom.size(), 1u);
    AX_EXPECT_EQ(decoded.memory.size(), 1u);
    AX_EXPECT(decoded.rom[0].id == "rom");
    AX_EXPECT_EQ(decoded.rom[0].offset, 0x40Bu);
    AX_EXPECT(decoded.rom[0].expect == set.rom[0].expect);
    AX_EXPECT(decoded.memory[0].condition.op == CompareOp::Greater);
    AX_EXPECT_EQ(decoded.memory[0].condition.value, 100u);
    AX_EXPECT(decoded.memory[0].enabled);
}

AX_TEST(patch_blob_matches_kotlin_golden_encoding) {
    // The same bytes are asserted by the Kotlin encoder test
    // (advance-engine PatchBlobEncoderTest) — the cross-language contract.
    const char* golden =
        "41585042" "01000000" "01000000" "01000000"          // magic, version, counts
        "0300" "726f6d" "0b040000"                          // "rom" @ 0x40B
        "03000000" "4f4e21" "03000000" "4f4646"             // "ON!" expect "OFF"
        "0500" "6c69766573" "00000002"                      // "lives" @ 0x02000000
        "04000000" "09000000" "00000000"                    // bytes, no expect
        "00" "04" "04" "08000002" "64000000" "01";          // every frame, if u32[0x02000008] > 100
    AX_EXPECT(toHex(encodePatchBlob(sampleSet())) == std::string(golden));
}

AX_TEST(patch_blob_rejects_truncated_or_bad_input) {
    auto blob = encodePatchBlob(sampleSet());
    PatchSet out;
    std::string err;
    for (size_t cut : {size_t{3}, size_t{10}, blob.size() / 2, blob.size() - 1}) {
        AX_EXPECT(!decodePatchBlob(blob.data(), cut, &out, &err));
        AX_EXPECT(out.memory.empty() && out.rom.empty());
    }
    auto bad = blob;
    bad[4] = 9;  // version
    AX_EXPECT(!decodePatchBlob(bad.data(), bad.size(), &out, &err));
    auto trailing = blob;
    trailing.push_back(0);
    AX_EXPECT(!decodePatchBlob(trailing.data(), trailing.size(), &out, &err));
    AX_EXPECT(decodePatchBlob(nullptr, 0, &out, &err));  // empty = no patches
}
