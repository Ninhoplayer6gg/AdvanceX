// SPDX-License-Identifier: MPL-2.0
// Writes files on a worker thread so fsync() latency never stalls the
// emulation thread (which would cause audio crackle).
//
// Requests are coalesced per path: if a newer write for the same file is
// queued before the previous one started, only the newest is written.
#pragma once

#include <condition_variable>
#include <cstdint>
#include <functional>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace ax::runtime {

class BackgroundWriter {
public:
    /// Called on the worker thread after each write attempt.
    using Callback = std::function<void(const std::string& path, bool ok, const std::string& error)>;

    explicit BackgroundWriter(Callback callback = nullptr);
    ~BackgroundWriter();

    void submit(const std::string& path, std::vector<uint8_t> data, bool keepBackup);
    /// Blocks until all queued writes are finished.
    void drain();
    bool idle() const;

private:
    struct Job {
        std::vector<uint8_t> data;
        bool keepBackup = true;
    };
    void run();

    Callback callback_;
    mutable std::mutex mutex_;
    std::condition_variable cv_;
    std::condition_variable doneCv_;
    std::map<std::string, Job> pending_;
    bool busy_ = false;
    bool stop_ = false;
    std::thread thread_;
};

}  // namespace ax::runtime
