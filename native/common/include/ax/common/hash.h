// SPDX-License-Identifier: MPL-2.0
// Small, dependency-free hashing helpers used across native subsystems.
#pragma once

#include <cstddef>
#include <cstdint>

namespace ax::hash {

/// 64-bit FNV-1a. Fast and stable across platforms; used for asset
/// fingerprints (sprite/tile replacement keys). Not cryptographic.
constexpr uint64_t kFnvOffset = 0xcbf29ce484222325ull;
constexpr uint64_t kFnvPrime = 0x100000001b3ull;

inline uint64_t fnv1a64(const void* data, size_t size, uint64_t seed = kFnvOffset) {
    const auto* p = static_cast<const uint8_t*>(data);
    uint64_t h = seed;
    for (size_t i = 0; i < size; ++i) {
        h ^= p[i];
        h *= kFnvPrime;
    }
    return h;
}

/// Standard CRC-32 (IEEE 802.3, same as zlib/PNG/ZIP).
uint32_t crc32(const void* data, size_t size, uint32_t previous = 0);

}  // namespace ax::hash
