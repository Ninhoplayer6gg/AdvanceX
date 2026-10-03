// SPDX-License-Identifier: MPL-2.0
#include "ax/common/log.h"

#include <chrono>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <deque>
#include <mutex>

#if defined(__ANDROID__)
#include <android/log.h>
#endif

namespace ax::log {
namespace {

constexpr size_t kRingCapacity = 256;

struct State {
    std::mutex mutex;
    FILE* file = nullptr;
    std::string path;
    size_t maxBytes = 512 * 1024;
    size_t written = 0;
    Level minLevel = Level::Info;
    std::deque<std::string> ring;
};

State& state() {
    static State s;
    return s;
}

const char* levelName(Level level) {
    switch (level) {
        case Level::Debug: return "D";
        case Level::Info: return "I";
        case Level::Warn: return "W";
        case Level::Error: return "E";
    }
    return "?";
}

void rotateLocked(State& s) {
    if (s.file) {
        fclose(s.file);
        s.file = nullptr;
    }
    if (s.path.empty()) {
        return;
    }
    std::string old = s.path + ".1";
    std::rename(s.path.c_str(), old.c_str());
    s.file = std::fopen(s.path.c_str(), "w");
    s.written = 0;
}

}  // namespace

void setFile(const std::string& path, size_t maxBytes) {
    State& s = state();
    std::lock_guard<std::mutex> lock(s.mutex);
    if (s.file) {
        fclose(s.file);
        s.file = nullptr;
    }
    s.path = path;
    s.maxBytes = maxBytes;
    s.written = 0;
    if (path.empty()) {
        return;
    }
    s.file = std::fopen(path.c_str(), "a");
    if (s.file) {
        std::fseek(s.file, 0, SEEK_END);
        long pos = std::ftell(s.file);
        s.written = pos > 0 ? static_cast<size_t>(pos) : 0;
        if (s.written > s.maxBytes) {
            rotateLocked(s);
        }
    }
}

void setMinLevel(Level level) {
    State& s = state();
    std::lock_guard<std::mutex> lock(s.mutex);
    s.minLevel = level;
}

void writeV(Level level, const char* tag, const char* fmt, va_list args) {
    State& s = state();
    char message[1024];
    va_list copy;
    va_copy(copy, args);
    std::vsnprintf(message, sizeof(message), fmt, copy);
    va_end(copy);

    {
        // Level check without holding the lock for long.
        std::lock_guard<std::mutex> lock(s.mutex);
        if (static_cast<int>(level) < static_cast<int>(s.minLevel)) {
            return;
        }
    }

#if defined(__ANDROID__)
    int prio = ANDROID_LOG_INFO;
    switch (level) {
        case Level::Debug: prio = ANDROID_LOG_DEBUG; break;
        case Level::Info: prio = ANDROID_LOG_INFO; break;
        case Level::Warn: prio = ANDROID_LOG_WARN; break;
        case Level::Error: prio = ANDROID_LOG_ERROR; break;
    }
    __android_log_print(prio, "AdvanceX", "[%s] %s", tag, message);
#else
    std::fprintf(stderr, "[%s/%s] %s\n", levelName(level), tag, message);
#endif

    using namespace std::chrono;
    auto now = system_clock::now();
    std::time_t secs = system_clock::to_time_t(now);
    int millis = static_cast<int>(duration_cast<milliseconds>(now.time_since_epoch()).count() % 1000);
    std::tm tm{};
#if defined(_WIN32)
    localtime_s(&tm, &secs);
#else
    localtime_r(&secs, &tm);
#endif
    char line[1200];
    int len = std::snprintf(line, sizeof(line), "%04d-%02d-%02d %02d:%02d:%02d.%03d %s/%s: %s", tm.tm_year + 1900,
                            tm.tm_mon + 1, tm.tm_mday, tm.tm_hour, tm.tm_min, tm.tm_sec, millis, levelName(level), tag,
                            message);
    if (len < 0) {
        return;
    }

    std::lock_guard<std::mutex> lock(s.mutex);
    s.ring.emplace_back(line);
    while (s.ring.size() > kRingCapacity) {
        s.ring.pop_front();
    }
    if (s.file) {
        std::fputs(line, s.file);
        std::fputc('\n', s.file);
        s.written += static_cast<size_t>(len) + 1;
        if (level >= Level::Warn) {
            std::fflush(s.file);
        }
        if (s.written > s.maxBytes) {
            rotateLocked(s);
        }
    }
}

void write(Level level, const char* tag, const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    writeV(level, tag, fmt, args);
    va_end(args);
}

std::vector<std::string> recentLines(size_t maxLines) {
    State& s = state();
    std::lock_guard<std::mutex> lock(s.mutex);
    size_t start = s.ring.size() > maxLines ? s.ring.size() - maxLines : 0;
    return {s.ring.begin() + static_cast<std::ptrdiff_t>(start), s.ring.end()};
}

void flush() {
    State& s = state();
    std::lock_guard<std::mutex> lock(s.mutex);
    if (s.file) {
        std::fflush(s.file);
    }
}

}  // namespace ax::log
