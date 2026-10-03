// SPDX-License-Identifier: MPL-2.0
// Lock-free single-producer / single-consumer ring buffer.
//
// Used between the emulation thread (producer) and the real-time audio
// callback (consumer). Neither side ever blocks or allocates, which keeps the
// audio callback safe from priority inversion and GC-like pauses.
#pragma once

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstring>
#include <memory>

namespace ax {

template <typename T>
class SpscRing {
public:
    explicit SpscRing(size_t capacity = 0) { reset(capacity); }

    /// Not thread-safe: call only while neither side is active.
    void reset(size_t capacity) {
        // One slot is kept empty to distinguish full from empty.
        capacity_ = capacity + 1;
        buffer_.reset(capacity ? new T[capacity_] : nullptr);
        head_.store(0, std::memory_order_relaxed);
        tail_.store(0, std::memory_order_relaxed);
    }

    size_t capacity() const { return capacity_ ? capacity_ - 1 : 0; }

    size_t size() const {
        size_t head = head_.load(std::memory_order_acquire);
        size_t tail = tail_.load(std::memory_order_acquire);
        return head >= tail ? head - tail : capacity_ - tail + head;
    }

    size_t freeSpace() const { return capacity() - size(); }

    /// Producer side. Returns the number of items actually written.
    size_t push(const T* items, size_t count) {
        if (!capacity_) {
            return 0;
        }
        size_t head = head_.load(std::memory_order_relaxed);
        size_t tail = tail_.load(std::memory_order_acquire);
        size_t free = head >= tail ? capacity_ - 1 - (head - tail) : tail - head - 1;
        count = std::min(count, free);
        size_t first = std::min(count, capacity_ - head);
        std::memcpy(buffer_.get() + head, items, first * sizeof(T));
        std::memcpy(buffer_.get(), items + first, (count - first) * sizeof(T));
        head_.store((head + count) % capacity_, std::memory_order_release);
        return count;
    }

    /// Consumer side. Returns the number of items actually read.
    size_t pop(T* out, size_t count) {
        if (!capacity_) {
            return 0;
        }
        size_t tail = tail_.load(std::memory_order_relaxed);
        size_t head = head_.load(std::memory_order_acquire);
        size_t available = head >= tail ? head - tail : capacity_ - tail + head;
        count = std::min(count, available);
        size_t first = std::min(count, capacity_ - tail);
        std::memcpy(out, buffer_.get() + tail, first * sizeof(T));
        std::memcpy(out + first, buffer_.get(), (count - first) * sizeof(T));
        tail_.store((tail + count) % capacity_, std::memory_order_release);
        return count;
    }

    /// Consumer side: drops everything currently buffered.
    void clear() { tail_.store(head_.load(std::memory_order_acquire), std::memory_order_release); }

private:
    std::unique_ptr<T[]> buffer_;
    size_t capacity_ = 0;
    std::atomic<size_t> head_{0};
    std::atomic<size_t> tail_{0};
};

}  // namespace ax
