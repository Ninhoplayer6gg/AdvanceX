// SPDX-License-Identifier: MPL-2.0
// Binary hand-off format for runtime patches (Kotlin Advance Engine -> native).
//
// Patch definitions are authored as JSON and validated in Kotlin, then sent
// across JNI as one compact, versioned blob instead of dozens of arrays.
// All integers are little-endian.
//
//   "AXPB"  u32 version(1)  u32 romCount  u32 memCount
//   rom patch:  str id, u32 offset, bytes data, bytes expect
//   mem patch:  str id, u32 address, bytes data, bytes expect,
//               u8 mode, u8 condOp, u8 condWidth, u32 condAddress, u32 condValue,
//               u8 enabled
//   str   = u16 length + UTF-8
//   bytes = u32 length + raw bytes
//
// The decoder is strict: any truncation or out-of-range value rejects the
// whole blob (the game then simply runs unpatched).
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "ax/engine/patch_engine.h"

namespace ax::runtime {

struct PatchSet {
    std::vector<engine::RomPatch> rom;
    std::vector<engine::MemoryPatch> memory;
};

bool decodePatchBlob(const uint8_t* data, size_t size, PatchSet* out, std::string* error);
std::vector<uint8_t> encodePatchBlob(const PatchSet& set);

}  // namespace ax::runtime
