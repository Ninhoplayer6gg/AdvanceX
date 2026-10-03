// SPDX-License-Identifier: MPL-2.0
// Local native crash reports.
//
// On a fatal signal the handler writes a short report (signal, fault
// address, what was running) to a file the app reads on next launch, then
// lets the default handler run so the system still records the crash. Only
// async-signal-safe calls are used inside the handler.
//
// Nothing is uploaded anywhere: reports stay on the device.
#pragma once

#include <string>

namespace ax::runtime {

/// Installs the handler. The report file is opened now (append mode) so the
/// signal handler does not need to allocate or open files.
bool installCrashHandler(const std::string& reportPath);

/// Describes what is running (game id, Advance Mode, active features).
/// Copied into a fixed buffer; safe to call often.
void setCrashContext(const std::string& context);

}  // namespace ax::runtime
