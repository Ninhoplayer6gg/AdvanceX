// SPDX-License-Identifier: MPL-2.0
// Rewind history built from periodic save-state snapshots.
//
// Only the newest snapshot is kept in full. Older ones are stored as XOR
// deltas against their successor, compressed with a zero-run encoding, so a
// typical GBA state (~400 KiB) costs a few KiB per snapshot. The oldest
// entries are dropped when the memory or duration budget is exceeded.
#pragma once

#include <cstddef>
#include <cstdint>
#include <deque>
#include <vector>

namespace ax::engine {

struct RewindConfig {
    bool enabled = false;
    int intervalFrames = 6;        // one snapshot every N emulated frames
    int maxSeconds = 30;           // history length
    size_t maxBytes = 32u << 20;   // memory budget for compressed history
};

class RewindBuffer {
public:
    void configure(const RewindConfig& config, double framesPerSecond);
    const RewindConfig& config() const { return config_; }

    /// Records a new snapshot (full serialized state).
    void push(const std::vector<uint8_t>& state);
    /// Moves one step back in time. On success `out` receives the state to
    /// load, which is the most recent snapshot not yet rewound past.
    bool pop(std::vector<uint8_t>* out);

    void clear();
    size_t count() const { return deltas_.size() + (head_.empty() ? 0 : 1); }
    size_t memoryUsage() const { return deltaBytes_ + head_.size(); }
    /// Approximate seconds of history currently stored.
    double secondsStored() const;

    // Exposed for tests.
    static std::vector<uint8_t> encodeXor(const std::vector<uint8_t>& a, const std::vector<uint8_t>& b);
    static bool decodeXor(const std::vector<uint8_t>& delta, std::vector<uint8_t>* inOut);

private:
    void enforceLimits();

    RewindConfig config_;
    double fps_ = 59.7275;
    size_t maxEntries_ = 0;
    std::vector<uint8_t> head_;               // newest full state
    std::deque<std::vector<uint8_t>> deltas_; // back = delta from head to previous
    size_t deltaBytes_ = 0;
};

}  // namespace ax::engine
