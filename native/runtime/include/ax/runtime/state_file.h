// SPDX-License-Identifier: MPL-2.0
// The .axstate save-state container.
//
//   offset  size  field
//   0       4     magic "AXST"
//   4       4     format version (1)
//   8       4     header size (96)
//   12      4     flags (bit 0: payload is zlib-compressed)
//   16      16    core id, NUL padded ("mgba")
//   32      16    core version, NUL padded ("0.10.5")
//   48      4     CRC-32 of the ROM image the state was made with
//   52      4     ROM size in bytes
//   56      8     creation time, Unix epoch milliseconds
//   64      4     emulated frame counter
//   68      4     uncompressed state size
//   72      4     payload size
//   76      4     CRC-32 of the payload
//   80      16    reserved (zero)
//   96      ...   payload
//
// All integers are little-endian. Every field is validated on load, so a
// truncated or foreign file is rejected instead of being fed to the core.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace ax::runtime {

struct StateFileInfo {
    uint32_t formatVersion = 1;
    std::string coreId;
    std::string coreVersion;
    uint32_t romCrc32 = 0;
    uint32_t romSize = 0;
    int64_t timestampMs = 0;
    uint32_t frameCounter = 0;
};

bool encodeStateFile(const std::vector<uint8_t>& state, const StateFileInfo& info, std::vector<uint8_t>* out,
                     std::string* error);
bool decodeStateFile(const std::vector<uint8_t>& file, std::vector<uint8_t>* state, StateFileInfo* info,
                     std::string* error);

bool writeStateFile(const std::string& path, const std::vector<uint8_t>& state, const StateFileInfo& info,
                    std::string* error);
bool readStateFile(const std::string& path, std::vector<uint8_t>* state, StateFileInfo* info, std::string* error);

}  // namespace ax::runtime
