// SPDX-License-Identifier: MPL-2.0
// AdvanceX native logging.
//
// All native subsystems log through this facade. Messages go to the platform
// sink (logcat on Android, stderr on desktop hosts), to an optional log file,
// and to a small in-memory ring buffer that crash reports can include.
#pragma once

#include <cstdarg>
#include <string>
#include <vector>

namespace ax::log {

enum class Level : int { Debug = 0, Info = 1, Warn = 2, Error = 3 };

/// Opens (or rotates) the native log file. Safe to call more than once.
/// Passing an empty path disables the file sink.
void setFile(const std::string& path, size_t maxBytes = 512 * 1024);

/// Messages below this level are dropped.
void setMinLevel(Level level);

void write(Level level, const char* tag, const char* fmt, ...)
#if defined(__GNUC__)
    __attribute__((format(printf, 3, 4)))
#endif
    ;

void writeV(Level level, const char* tag, const char* fmt, va_list args);

/// Returns up to `maxLines` most recent log lines (oldest first).
std::vector<std::string> recentLines(size_t maxLines = 200);

/// Flushes the file sink.
void flush();

}  // namespace ax::log

#define AX_LOGD(tag, ...) ::ax::log::write(::ax::log::Level::Debug, tag, __VA_ARGS__)
#define AX_LOGI(tag, ...) ::ax::log::write(::ax::log::Level::Info, tag, __VA_ARGS__)
#define AX_LOGW(tag, ...) ::ax::log::write(::ax::log::Level::Warn, tag, __VA_ARGS__)
#define AX_LOGE(tag, ...) ::ax::log::write(::ax::log::Level::Error, tag, __VA_ARGS__)
