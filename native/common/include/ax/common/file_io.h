// SPDX-License-Identifier: MPL-2.0
// Crash-safe file helpers.
//
// `writeFileAtomic` never leaves a half-written destination file behind: data
// is written to `<path>.tmp`, flushed with fsync, and only then renamed over
// the destination. When `keepBackup` is set, the previous contents are first
// preserved as `<path>.bak` so a bad write can always be rolled back.
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace ax::fileio {

struct WriteResult {
    bool ok = false;
    std::string error;
};

WriteResult writeFileAtomic(const std::string& path, const void* data, size_t size, bool keepBackup);

/// Reads a whole file. Returns false (and leaves `out` empty) if it cannot be read.
bool readFile(const std::string& path, std::vector<uint8_t>* out, size_t maxBytes = 64u * 1024u * 1024u);

bool fileExists(const std::string& path);

/// Reads `path`, falling back to `<path>.bak` when the primary file is missing
/// or empty. `usedBackup` reports which one was used.
bool readFileWithBackup(const std::string& path, std::vector<uint8_t>* out, bool* usedBackup);

/// Creates a directory and its parents (like `mkdir -p`).
bool makeDirs(const std::string& path);

}  // namespace ax::fileio
