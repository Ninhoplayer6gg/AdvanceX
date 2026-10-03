// SPDX-License-Identifier: MPL-2.0
#include "ax/runtime/background_writer.h"

#include "ax/common/file_io.h"
#include "ax/common/log.h"

namespace ax::runtime {

BackgroundWriter::BackgroundWriter(Callback callback) : callback_(std::move(callback)) {
    thread_ = std::thread([this] { run(); });
}

BackgroundWriter::~BackgroundWriter() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        stop_ = true;
    }
    cv_.notify_all();
    if (thread_.joinable()) thread_.join();
}

void BackgroundWriter::submit(const std::string& path, std::vector<uint8_t> data, bool keepBackup) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        pending_[path] = Job{std::move(data), keepBackup};
    }
    cv_.notify_all();
}

void BackgroundWriter::drain() {
    std::unique_lock<std::mutex> lock(mutex_);
    doneCv_.wait(lock, [this] { return pending_.empty() && !busy_; });
}

bool BackgroundWriter::idle() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return pending_.empty() && !busy_;
}

void BackgroundWriter::run() {
    std::unique_lock<std::mutex> lock(mutex_);
    for (;;) {
        cv_.wait(lock, [this] { return stop_ || !pending_.empty(); });
        if (pending_.empty()) {
            if (stop_) break;
            continue;
        }
        auto it = pending_.begin();
        std::string path = it->first;
        Job job = std::move(it->second);
        pending_.erase(it);
        busy_ = true;
        lock.unlock();

        auto result = ax::fileio::writeFileAtomic(path, job.data.data(), job.data.size(), job.keepBackup);
        if (!result.ok) {
            AX_LOGE("BackgroundWriter", "Write failed: %s", result.error.c_str());
        }
        if (callback_) callback_(path, result.ok, result.error);

        lock.lock();
        busy_ = false;
        doneCv_.notify_all();
    }
    // Leave nothing behind: flush whatever arrived during shutdown.
    for (auto& entry : pending_) {
        ax::fileio::writeFileAtomic(entry.first, entry.second.data.data(), entry.second.data.size(),
                                    entry.second.keepBackup);
    }
    pending_.clear();
    doneCv_.notify_all();
}

}  // namespace ax::runtime
