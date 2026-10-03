// SPDX-License-Identifier: MPL-2.0
#include "ax/common/hash.h"

#include <zlib.h>

namespace ax::hash {

uint32_t crc32(const void* data, size_t size, uint32_t previous) {
    uLong crc = previous;
    const auto* p = static_cast<const Bytef*>(data);
    // zlib takes uInt lengths; feed large buffers in chunks.
    while (size > 0) {
        uInt chunk = size > 0x40000000u ? 0x40000000u : static_cast<uInt>(size);
        crc = ::crc32(crc, p, chunk);
        p += chunk;
        size -= chunk;
    }
    return static_cast<uint32_t>(crc);
}

}  // namespace ax::hash
