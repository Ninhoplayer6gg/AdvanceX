// SPDX-License-Identifier: MPL-2.0
package io.advancex.app

import android.app.Application
import android.os.Build
import io.advancex.app.bridge.NativeBridge
import io.advancex.app.util.AndroidLogSink
import io.advancex.core.emulator.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Application entry point: sets up logging, local crash reports and the
 * native library. Nothing here touches the network; crash reports stay on
 * the device.
 */
class AdvanceXApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val services = AppServices.get(this)
        Log.sink = AndroidLogSink(services.logs.appLog)
        installCrashHandler(services)
        val nativeOk = runCatching {
            NativeBridge.init(services.logs.nativeLog.absolutePath, services.logs.nativeCrashFile.absolutePath)
        }.onFailure { Log.e(TAG, "Native library failed to load", it) }.getOrDefault(false)
        Log.i(TAG, "AdvanceX ${BuildInfo.VERSION_NAME} on Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "${Build.MANUFACTURER} ${Build.MODEL}, ABIs ${Build.SUPPORTED_ABIS.joinToString()}, native=$nativeOk")
        services.collectPreviousCrash()
    }

    private fun installCrashHandler(services: AppServices) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val report = buildString {
                    appendLine("=== AdvanceX crash report (Java/Kotlin) ===")
                    appendLine("version: ${BuildInfo.VERSION_NAME}")
                    appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("thread: ${thread.name}")
                    appendLine()
                    appendLine(trace)
                    appendLine("--- recent log ---")
                    append(services.logs.tail(150))
                }
                services.logs.crashesDir.mkdirs()
                File(services.logs.crashesDir, "crash-$stamp.txt").writeText(report)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        private const val TAG = "App"
    }
}

/** Version info without depending on a generated BuildConfig (works in every build mode). */
object BuildInfo {
    const val VERSION_NAME = io.advancex.engine.AdvanceX.VERSION
}
