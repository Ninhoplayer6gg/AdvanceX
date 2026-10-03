// SPDX-License-Identifier: MPL-2.0
#include "ax/engine/rewind_buffer.h"

#include <algorithm>

namespace ax::engine {
namespace {

// Delta format: repeated [varint zeroRun][varint literalCount][literal bytes].
void putVarint(std::vector<uint8_t>* out, size_t v) {
    while (v >= 0x80) {
        out->push_back(static_cast<uint8_t>(v | 0x80));
        v >>= 7;
    }
    out->push_back(static_cast<uint8_t>(v));
}

bool getVarint(const std::vector<uint8_t>& in, size_t* pos, size_t* v) {
    size_t result = 0;
    int shift = 0;
    while (*pos < in.size() && shift < 63) {
        uint8_t b = in[(*pos)++];
        result |= static_cast<size_t>(b & 0x7F) << shift;
        if (!(b & 0x80)) {
            *v = result;
            return true;
        }
        shift += 7;
    }
    return false;
}

}  // namespace

std::vector<uint8_t> RewindBuffer::encodeXor(const std::vector<uint8_t>& a, const std::vector<uint8_t>& b) {
    std::vector<uint8_t> out;
    size_t n = std::min(a.size(), b.size());
    out.reserve(n / 16);
    putVarint(&out, n);
    size_t i = 0;
    while (i < n) {
        size_t zeros = 0;
        while (i + zeros < n && a[i + zeros] == b[i + zeros]) ++zeros;
        size_t litStart = i + zeros;
        size_t lit = 0;
        // A literal run ends at the first stretch of >= 4 equal bytes.
        while (litStart + lit < n) {
            if (a[litStart + lit] == b[litStart + lit]) {
                size_t same = 0;
                while (litStart + lit + same < n && same < 4 && a[litStart + lit + same] == b[litStart + lit + same]) {
                    ++same;
                }
                if (same >= 4 || litStart + lit + same == n) break;
                lit += same;
                continue;
            }
            ++lit;
        }
        putVarint(&out, zeros);
        putVarint(&out, lit);
        for (size_t k = 0; k < lit; ++k) {
            out.push_back(static_cast<uint8_t>(a[litStart + k] ^ b[litStart + k]));
        }
        i = litStart + lit;
    }
    out.shrink_to_fit();
    return out;
}

bool RewindBuffer::decodeXor(const std::vector<uint8_t>& delta, std::vector<uint8_t>* inOut) {
    size_t pos = 0;
    size_t n = 0;
    if (!getVarint(delta, &pos, &n) || n != inOut->size()) return false;
    size_t i = 0;
    while (i < n) {
        size_t zeros = 0, lit = 0;
        if (!getVarint(delta, &pos, &zeros) || !getVarint(delta, &pos, &lit)) return false;
        i += zeros;
        if (i + lit > n || pos + lit > delta.size()) return false;
        for (size_t k = 0; k < lit; ++k) (*inOut)[i + k] ^= delta[pos + k];
        i += lit;
        pos += lit;
    }
    return pos == delta.size();
}

void RewindBuffer::configure(const RewindConfig& config, double framesPerSecond) {
    config_ = config;
    config_.intervalFrames = std::clamp(config_.intervalFrames, 1, 120);
    config_.maxSeconds = std::clamp(config_.maxSeconds, 1, 600);
    fps_ = framesPerSecond > 1.0 ? framesPerSecond : 59.7275;
    maxEntries_ = static_cast<size_t>(config_.maxSeconds * fps_ / config_.intervalFrames) + 1;
    if (!config_.enabled) {
        clear();
    } else {
        enforceLimits();
    }
}

void RewindBuffer::push(const std::vector<uint8_t>& state) {
    if (!config_.enabled || state.empty()) return;
    if (!head_.empty()) {
        if (head_.size() != state.size()) {
            clear();  // core layout changed (different game): restart history
        } else {
            std::vector<uint8_t> delta = encodeXor(state, head_);
            deltaBytes_ += delta.size();
            deltas_.push_back(std::move(delta));
        }
    }
    head_ = state;
    enforceLimits();
}

bool RewindBuffer::pop(std::vector<uint8_t>* out) {
    if (head_.empty()) return false;
    *out = head_;
    if (deltas_.empty()) {
        head_.clear();
        return true;
    }
    std::vector<uint8_t> delta = std::move(deltas_.back());
    deltas_.pop_back();
    deltaBytes_ -= delta.size();
    if (!decodeXor(delta, &head_)) {
        // Should never happen; drop the history rather than load garbage.
        clear();
    }
    return true;
}

void RewindBuffer::clear() {
    head_.clear();
    deltas_.clear();
    deltaBytes_ = 0;
}

double RewindBuffer::secondsStored() const {
    return static_cast<double>(count()) * config_.intervalFrames / fps_;
}

void RewindBuffer::enforceLimits() {
    while (!deltas_.empty() && (deltas_.size() + 1 > maxEntries_ || memoryUsage() > config_.maxBytes)) {
        deltaBytes_ -= deltas_.front().size();
        deltas_.pop_front();
    }
}

}  // namespace ax::engine
