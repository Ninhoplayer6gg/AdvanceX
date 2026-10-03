// SPDX-License-Identifier: MPL-2.0
package io.advancex.app.util

import io.advancex.core.emulator.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Locations of the on-device logs and crash reports. */
class LogFiles(val dir: File) {
    val appLog = File(dir, "app.log")
    val nativeLog = File(dir, "native.log")
    /** Written by the native signal handler; collected on the next launch. */
    val nativeCrashFile = File(dir, "native-crash.txt")
    val crashesDir = File(dir, "crashes")

    init {
        dir.mkdirs()
        crashesDir.mkdirs()
    }

    fun crashReports(): List<File> =
        crashesDir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun tail(lines: Int): String {
        val all = (runCatching { appLog.readLines() }.getOrDefault(emptyList()) +
            runCatching { nativeLog.readLines() }.getOrDefault(emptyList()))
        return all.takeLast(lines).joinToString("\n")
    }

    /** Everything useful for a bug report, as one text document. */
    fun exportText(): String = buildString {
        appendLine("AdvanceX diagnostics export")
        appendLine()
        crashReports().take(5).forEach {
            appendLine("##### ${it.name}")
            appendLine(it.readText())
        }
        for (f in listOf(File(appLog.path + ".1"), appLog, File(nativeLog.path + ".1"), nativeLog)) {
            if (!f.isFile) continue
            appendLine("##### ${f.name}")
            appendLine(f.readText())
        }
    }

    fun clear() {
        crashesDir.listFiles()?.forEach { it.delete() }
        listOf(appLog, nativeLog, File(appLog.path + ".1"), File(nativeLog.path + ".1")).forEach { it.delete() }
    }
}

/** Sends Kotlin logs to logcat and to the rotating app log file. */
class AndroidLogSink(private val file: File, private val maxBytes: Long = 512 * 1024) : Log.Sink {
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    override fun write(level: Log.Level, tag: String, message: String, error: Throwable?) {
        val priority = when (level) {
            Log.Level.DEBUG -> android.util.Log.DEBUG
            Log.Level.INFO -> android.util.Log.INFO
            Log.Level.WARN -> android.util.Log.WARN
            Log.Level.ERROR -> android.util.Log.ERROR
        }
        android.util.Log.println(priority, "AdvanceX", "[$tag] $message" + (error?.let { "\n" + android.util.Log.getStackTraceString(it) } ?: ""))
        if (level == Log.Level.DEBUG) return
        runCatching {
            if (file.length() > maxBytes) file.renameTo(File(file.path + ".1"))
            FileWriter(file, true).use { w ->
                w.append(format.format(Date())).append(' ').append(level.name[0]).append('/').append(tag).append(": ")
                    .append(message)
                if (error != null) w.append(" (").append(error.toString()).append(')')
                w.append('\n')
            }
        }
    }
}
