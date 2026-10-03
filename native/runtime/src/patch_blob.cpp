// SPDX-License-Identifier: MPL-2.0
#include "ax/runtime/patch_blob.h"

#include <cstring>

namespace ax::runtime {
namespace {

constexpr uint32_t kVersion = 1;
constexpr uint32_t kMaxPatches = 4096;
constexpr uint32_t kMaxBytes = 4096;

class Reader {
public:
    Reader(const uint8_t* data, size_t size) : data_(data), size_(size) {}
    bool u8(uint8_t* v) {
        if (pos_ + 1 > size_) return false;
        *v = data_[pos_++];
        return true;
    }
    bool u16(uint16_t* v) {
        if (pos_ + 2 > size_) return false;
        *v = static_cast<uint16_t>(data_[pos_] | (data_[pos_ + 1] << 8));
        pos_ += 2;
        return true;
    }
    bool u32(uint32_t* v) {
        if (pos_ + 4 > size_) return false;
        *v = static_cast<uint32_t>(data_[pos_]) | (static_cast<uint32_t>(data_[pos_ + 1]) << 8) |
             (static_cast<uint32_t>(data_[pos_ + 2]) << 16) | (static_cast<uint32_t>(data_[pos_ + 3]) << 24);
        pos_ += 4;
        return true;
    }
    bool str(std::string* s) {
        uint16_t n = 0;
        if (!u16(&n) || pos_ + n > size_) return false;
        s->assign(reinterpret_cast<const char*>(data_ + pos_), n);
        pos_ += n;
        return true;
    }
    bool bytes(std::vector<uint8_t>* b) {
        uint32_t n = 0;
        if (!u32(&n) || n > kMaxBytes || pos_ + n > size_) return false;
        b->assign(data_ + pos_, data_ + pos_ + n);
        pos_ += n;
        return true;
    }
    bool atEnd() const { return pos_ == size_; }

private:
    const uint8_t* data_;
    size_t size_;
    size_t pos_ = 0;
};

class Writer {
public:
    void u8(uint8_t v) { out.push_back(v); }
    void u16(uint16_t v) {
        out.push_back(static_cast<uint8_t>(v));
        out.push_back(static_cast<uint8_t>(v >> 8));
    }
    void u32(uint32_t v) {
        for (int i = 0; i < 4; ++i) out.push_back(static_cast<uint8_t>(v >> (8 * i)));
    }
    void str(const std::string& s) {
        u16(static_cast<uint16_t>(s.size()));
        out.insert(out.end(), s.begin(), s.end());
    }
    void bytes(const std::vector<uint8_t>& b) {
        u32(static_cast<uint32_t>(b.size()));
        out.insert(out.end(), b.begin(), b.end());
    }
    std::vector<uint8_t> out;
};

}  // namespace

bool decodePatchBlob(const uint8_t* data, size_t size, PatchSet* out, std::string* error) {
    auto fail = [&](const char* why) {
        if (error) *error = why;
        *out = PatchSet{};
        return false;
    };
    *out = PatchSet{};
    if (size == 0) return true;  // no patches
    if (size < 16 || std::memcmp(data, "AXPB", 4) != 0) return fail("not a patch blob");
    Reader r(data + 4, size - 4);
    uint32_t version = 0, romCount = 0, memCount = 0;
    if (!r.u32(&version) || version != kVersion) return fail("unsupported patch blob version");
    if (!r.u32(&romCount) || !r.u32(&memCount) || romCount > kMaxPatches || memCount > kMaxPatches) {
        return fail("bad patch counts");
    }
    for (uint32_t i = 0; i < romCount; ++i) {
        engine::RomPatch p;
        if (!r.str(&p.id) || !r.u32(&p.offset) || !r.bytes(&p.bytes) || !r.bytes(&p.expect)) {
            return fail("truncated ROM patch");
        }
        out->rom.push_back(std::move(p));
    }
    for (uint32_t i = 0; i < memCount; ++i) {
        engine::MemoryPatch p;
        uint8_t mode = 0, op = 0, width = 0, enabled = 0;
        if (!r.str(&p.id) || !r.u32(&p.address) || !r.bytes(&p.bytes) || !r.bytes(&p.expect) || !r.u8(&mode) ||
            !r.u8(&op) || !r.u8(&width) || !r.u32(&p.condition.address) || !r.u32(&p.condition.value) ||
            !r.u8(&enabled)) {
            return fail("truncated memory patch");
        }
        if (mode > 1 || op > 4) return fail("invalid patch mode or condition");
        p.mode = static_cast<engine::PatchMode>(mode);
        p.condition.op = static_cast<engine::CompareOp>(op);
        p.condition.width = width;
        p.enabled = enabled != 0;
        out->memory.push_back(std::move(p));
    }
    if (!r.atEnd()) return fail("trailing data in patch blob");
    return true;
}

std::vector<uint8_t> encodePatchBlob(const PatchSet& set) {
    Writer w;
    w.out.insert(w.out.end(), {'A', 'X', 'P', 'B'});
    w.u32(kVersion);
    w.u32(static_cast<uint32_t>(set.rom.size()));
    w.u32(static_cast<uint32_t>(set.memory.size()));
    for (const auto& p : set.rom) {
        w.str(p.id);
        w.u32(p.offset);
        w.bytes(p.bytes);
        w.bytes(p.expect);
    }
    for (const auto& p : set.memory) {
        w.str(p.id);
        w.u32(p.address);
        w.bytes(p.bytes);
        w.bytes(p.expect);
        w.u8(static_cast<uint8_t>(p.mode));
        w.u8(static_cast<uint8_t>(p.condition.op));
        w.u8(p.condition.width);
        w.u32(p.condition.address);
        w.u32(p.condition.value);
        w.u8(p.enabled ? 1 : 0);
    }
    return w.out;
}

}  // namespace ax::runtime
