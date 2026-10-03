// SPDX-License-Identifier: MPL-2.0
#include "ax/common/file_io.h"

#include <cerrno>
#include <cstdio>
#include <cstring>

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace ax::fileio {
namespace {

std::string errnoString(const char* what, const std::string& path) {
    return std::string(what) + " '" + path + "': " + std::strerror(errno);
}

bool writeAll(int fd, const uint8_t* data, size_t size) {
    while (size > 0) {
        ssize_t n = ::write(fd, data, size);
        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }
            return false;
        }
        data += n;
        size -= static_cast<size_t>(n);
    }
    return true;
}

bool writeAndSync(const std::string& path, const void* data, size_t size, std::string* error) {
    int fd = ::open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) {
        *error = errnoString("cannot open", path);
        return false;
    }
    bool ok = writeAll(fd, static_cast<const uint8_t*>(data), size);
    if (!ok) {
        *error = errnoString("cannot write", path);
    } else if (::fsync(fd) != 0) {
        *error = errnoString("cannot fsync", path);
        ok = false;
    }
    ::close(fd);
    return ok;
}

void syncParentDir(const std::string& path) {
    size_t slash = path.find_last_of('/');
    std::string dir = slash == std::string::npos ? "." : path.substr(0, slash == 0 ? 1 : slash);
    int fd = ::open(dir.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd >= 0) {
        ::fsync(fd);
        ::close(fd);
    }
}

}  // namespace

WriteResult writeFileAtomic(const std::string& path, const void* data, size_t size, bool keepBackup) {
    WriteResult result;
    std::string tmp = path + ".tmp";
    if (!writeAndSync(tmp, data, size, &result.error)) {
        ::unlink(tmp.c_str());
        return result;
    }

    if (keepBackup && fileExists(path)) {
        // Copy (not rename) the current file to .bak so that `path` exists at
        // every instant: a crash at any point leaves a valid primary file.
        std::vector<uint8_t> previous;
        if (readFile(path, &previous) && !previous.empty()) {
            std::string bakTmp = path + ".bak.tmp";
            std::string bak = path + ".bak";
            std::string ignored;
            if (writeAndSync(bakTmp, previous.data(), previous.size(), &ignored)) {
                ::rename(bakTmp.c_str(), bak.c_str());
            } else {
                ::unlink(bakTmp.c_str());
            }
        }
    }

    if (::rename(tmp.c_str(), path.c_str()) != 0) {
        result.error = errnoString("cannot rename into", path);
        ::unlink(tmp.c_str());
        return result;
    }
    syncParentDir(path);
    result.ok = true;
    return result;
}

bool readFile(const std::string& path, std::vector<uint8_t>* out, size_t maxBytes) {
    out->clear();
    int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return false;
    }
    struct stat st {};
    if (::fstat(fd, &st) != 0 || st.st_size < 0 || static_cast<size_t>(st.st_size) > maxBytes) {
        ::close(fd);
        return false;
    }
    out->resize(static_cast<size_t>(st.st_size));
    size_t done = 0;
    while (done < out->size()) {
        ssize_t n = ::read(fd, out->data() + done, out->size() - done);
        if (n < 0 && errno == EINTR) {
            continue;
        }
        if (n <= 0) {
            ::close(fd);
            out->clear();
            return false;
        }
        done += static_cast<size_t>(n);
    }
    ::close(fd);
    return true;
}

bool fileExists(const std::string& path) {
    struct stat st {};
    return ::stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

bool readFileWithBackup(const std::string& path, std::vector<uint8_t>* out, bool* usedBackup) {
    if (usedBackup) {
        *usedBackup = false;
    }
    if (readFile(path, out) && !out->empty()) {
        return true;
    }
    if (readFile(path + ".bak", out) && !out->empty()) {
        if (usedBackup) {
            *usedBackup = true;
        }
        return true;
    }
    out->clear();
    return false;
}

bool makeDirs(const std::string& path) {
    if (path.empty()) {
        return false;
    }
    std::string current;
    size_t pos = 0;
    while (pos != std::string::npos) {
        pos = path.find('/', pos + 1);
        current = path.substr(0, pos);
        if (current.empty()) {
            continue;
        }
        if (::mkdir(current.c_str(), 0755) != 0 && errno != EEXIST) {
            return false;
        }
    }
    struct stat st {};
    return ::stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

}  // namespace ax::fileio
