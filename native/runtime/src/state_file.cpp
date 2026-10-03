// SPDX-License-Identifier: MPL-2.0
#include "ax/runtime/state_file.h"

#include <zlib.h>

#include <algorithm>
#include <cstring>

#include "ax/common/file_io.h"
#include "ax/common/hash.h"

namespace ax::runtime {
namespace {

constexpr uint32_t kHeaderSize = 96;
constexpr uint32_t kFormatVersion = 1;
constexpr uint32_t kFlagZlib = 1;
constexpr uint32_t kMaxStateSize = 16u << 20;

void put32(uint8_t* p, uint32_t v) {
    for (int i = 0; i < 4; ++i) p[i] = static_cast<uint8_t>(v >> (8 * i));
}
void put64(uint8_t* p, uint64_t v) {
    for (int i = 0; i < 8; ++i) p[i] = static_cast<uint8_t>(v >> (8 * i));
}
uint32_t get32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) | (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}
uint64_t get64(const uint8_t* p) {
    uint64_t v = 0;
    for (int i = 7; i >= 0; --i) v = (v << 8) | p[i];
    return v;
}
void putString(uint8_t* p, const std::string& s, size_t field) {
    std::memset(p, 0, field);
    std::memcpy(p, s.data(), std::min(s.size(), field - 1));
}
std::string getString(const uint8_t* p, size_t field) {
    size_t n = 0;
    while (n < field && p[n]) ++n;
    return std::string(reinterpret_cast<const char*>(p), n);
}

}  // namespace

bool encodeStateFile(const std::vector<uint8_t>& state, const StateFileInfo& info, std::vector<uint8_t>* out,
                     std::string* error) {
    if (state.empty() || state.size() > kMaxStateSize) {
        if (error) *error = "invalid state size";
        return false;
    }
    uLongf bound = compressBound(static_cast<uLong>(state.size()));
    out->assign(kHeaderSize + bound, 0);
    uLongf compressedSize = bound;
    if (compress2(out->data() + kHeaderSize, &compressedSize, state.data(), static_cast<uLong>(state.size()), 1) !=
        Z_OK) {
        if (error) *error = "compression failed";
        return false;
    }
    out->resize(kHeaderSize + compressedSize);
    uint8_t* h = out->data();
    std::memcpy(h, "AXST", 4);
    put32(h + 4, kFormatVersion);
    put32(h + 8, kHeaderSize);
    put32(h + 12, kFlagZlib);
    putString(h + 16, info.coreId, 16);
    putString(h + 32, info.coreVersion, 16);
    put32(h + 48, info.romCrc32);
    put32(h + 52, info.romSize);
    put64(h + 56, static_cast<uint64_t>(info.timestampMs));
    put32(h + 64, info.frameCounter);
    put32(h + 68, static_cast<uint32_t>(state.size()));
    put32(h + 72, static_cast<uint32_t>(compressedSize));
    put32(h + 76, ax::hash::crc32(h + kHeaderSize, compressedSize));
    return true;
}

bool decodeStateFile(const std::vector<uint8_t>& file, std::vector<uint8_t>* state, StateFileInfo* info,
                     std::string* error) {
    auto fail = [&](const char* why) {
        if (error) *error = why;
        state->clear();
        return false;
    };
    if (file.size() < kHeaderSize || std::memcmp(file.data(), "AXST", 4) != 0) return fail("not an AdvanceX state file");
    const uint8_t* h = file.data();
    uint32_t version = get32(h + 4);
    uint32_t headerSize = get32(h + 8);
    uint32_t flags = get32(h + 12);
    if (version != kFormatVersion) return fail("unsupported state file version");
    if (headerSize < kHeaderSize || headerSize > file.size()) return fail("corrupt state header");
    uint32_t rawSize = get32(h + 68);
    uint32_t payloadSize = get32(h + 72);
    uint32_t payloadCrc = get32(h + 76);
    if (rawSize == 0 || rawSize > kMaxStateSize) return fail("corrupt state size");
    if (static_cast<uint64_t>(headerSize) + payloadSize != file.size()) return fail("state file is truncated");
    const uint8_t* payload = h + headerSize;
    if (ax::hash::crc32(payload, payloadSize) != payloadCrc) return fail("state file checksum mismatch");

    StateFileInfo parsed;
    parsed.formatVersion = version;
    parsed.coreId = getString(h + 16, 16);
    parsed.coreVersion = getString(h + 32, 16);
    parsed.romCrc32 = get32(h + 48);
    parsed.romSize = get32(h + 52);
    parsed.timestampMs = static_cast<int64_t>(get64(h + 56));
    parsed.frameCounter = get32(h + 64);

    state->resize(rawSize);
    if (flags & kFlagZlib) {
        uLongf outSize = rawSize;
        if (uncompress(state->data(), &outSize, payload, payloadSize) != Z_OK || outSize != rawSize) {
            return fail("state payload could not be decompressed");
        }
    } else {
        if (payloadSize != rawSize) return fail("corrupt state payload");
        std::memcpy(state->data(), payload, rawSize);
    }
    if (info) *info = parsed;
    return true;
}

bool writeStateFile(const std::string& path, const std::vector<uint8_t>& state, const StateFileInfo& info,
                    std::string* error) {
    std::vector<uint8_t> encoded;
    if (!encodeStateFile(state, info, &encoded, error)) return false;
    auto result = ax::fileio::writeFileAtomic(path, encoded.data(), encoded.size(), true);
    if (!result.ok && error) *error = result.error;
    return result.ok;
}

bool readStateFile(const std::string& path, std::vector<uint8_t>* state, StateFileInfo* info, std::string* error) {
    std::vector<uint8_t> file;
    if (!ax::fileio::readFile(path, &file)) {
        if (error) *error = "cannot read " + path;
        return false;
    }
    return decodeStateFile(file, state, info, error);
}

}  // namespace ax::runtime
