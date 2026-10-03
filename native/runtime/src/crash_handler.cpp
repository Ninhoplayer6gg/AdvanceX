// SPDX-License-Identifier: MPL-2.0
#include "ax/runtime/crash_handler.h"

#include <fcntl.h>
#include <signal.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <ctime>

namespace ax::runtime {
namespace {

constexpr int kSignals[] = {SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL};
constexpr size_t kContextSize = 512;

int gReportFd = -1;
char gContext[kContextSize] = "no game running";
std::atomic<bool> gInstalled{false};
struct sigaction gPrevious[sizeof(kSignals) / sizeof(kSignals[0])];

void writeStr(int fd, const char* s) {
    size_t len = std::strlen(s);
    while (len > 0) {
        ssize_t n = ::write(fd, s, len);
        if (n <= 0) return;
        s += n;
        len -= static_cast<size_t>(n);
    }
}

void writeHex(int fd, uintptr_t value) {
    char buf[2 + sizeof(uintptr_t) * 2 + 1];
    buf[0] = '0';
    buf[1] = 'x';
    for (size_t i = 0; i < sizeof(uintptr_t) * 2; ++i) {
        unsigned nibble = (value >> ((sizeof(uintptr_t) * 2 - 1 - i) * 4)) & 0xF;
        buf[2 + i] = static_cast<char>(nibble < 10 ? '0' + nibble : 'a' + nibble - 10);
    }
    buf[sizeof(buf) - 1] = '\0';
    writeStr(fd, buf);
}

void writeDec(int fd, long long value) {
    char buf[24];
    int pos = 23;
    buf[pos] = '\0';
    bool negative = value < 0;
    unsigned long long v = negative ? static_cast<unsigned long long>(-value) : static_cast<unsigned long long>(value);
    do {
        buf[--pos] = static_cast<char>('0' + v % 10);
        v /= 10;
    } while (v && pos > 1);
    if (negative) buf[--pos] = '-';
    writeStr(fd, buf + pos);
}

const char* signalName(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS: return "SIGBUS";
        case SIGFPE: return "SIGFPE";
        case SIGILL: return "SIGILL";
        default: return "signal";
    }
}

void handler(int sig, siginfo_t* info, void* ucontext) {
    if (gReportFd >= 0) {
        writeStr(gReportFd, "=== AdvanceX native crash ===\nsignal: ");
        writeStr(gReportFd, signalName(sig));
        writeStr(gReportFd, "\nfault address: ");
        writeHex(gReportFd, reinterpret_cast<uintptr_t>(info ? info->si_addr : nullptr));
        writeStr(gReportFd, "\ntime: ");
        writeDec(gReportFd, static_cast<long long>(time(nullptr)));
        writeStr(gReportFd, "\ncontext: ");
        writeStr(gReportFd, gContext);
        writeStr(gReportFd, "\n\n");
        ::fsync(gReportFd);
    }
    // Restore the previous handler and re-raise so the platform crash
    // reporter (debuggerd/tombstones) still sees the crash.
    for (size_t i = 0; i < sizeof(kSignals) / sizeof(kSignals[0]); ++i) {
        if (kSignals[i] == sig) {
            sigaction(sig, &gPrevious[i], nullptr);
            break;
        }
    }
    (void) ucontext;
    raise(sig);
}

}  // namespace

bool installCrashHandler(const std::string& reportPath) {
    if (gInstalled.exchange(true)) return true;
    gReportFd = ::open(reportPath.c_str(), O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
    struct sigaction sa {};
    sa.sa_sigaction = handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    for (size_t i = 0; i < sizeof(kSignals) / sizeof(kSignals[0]); ++i) {
        sigaction(kSignals[i], &sa, &gPrevious[i]);
    }
    return gReportFd >= 0;
}

void setCrashContext(const std::string& context) {
    // Plain copy into a fixed buffer; a torn read during a crash only
    // garbles the context line, never the handler itself.
    size_t n = std::min(context.size(), kContextSize - 1);
    std::memcpy(gContext, context.data(), n);
    gContext[n] = '\0';
}

}  // namespace ax::runtime
