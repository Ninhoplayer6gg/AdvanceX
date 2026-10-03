// SPDX-License-Identifier: MPL-2.0
package io.advancex.core.emulator

/**
 * Minimal logging facade for the platform-independent modules. The Android
 * app installs a sink that writes to logcat and the on-device log file;
 * tests and tools can install their own.
 */
object Log {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    fun interface Sink {
        fun write(level: Level, tag: String, message: String, error: Throwable?)
    }

    @Volatile
    var sink: Sink = Sink { level, tag, message, error ->
        if (level >= Level.INFO) {
            System.err.println("[${level.name[0]}/$tag] $message" + (error?.let { " ($it)" } ?: ""))
        }
    }

    fun d(tag: String, message: String) = sink.write(Level.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = sink.write(Level.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = sink.write(Level.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = sink.write(Level.ERROR, tag, message, error)
}
