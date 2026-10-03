// SPDX-License-Identifier: MPL-2.0
package io.advancex.core.emulator

import java.io.File

/** What to run and where its data lives. */
data class LaunchRequest(
    val romFile: File,
    val gameId: String,
    val cartridgeSave: File,
    val biosFile: File?,
    val settings: ResolvedSettings,
    /** Encoded runtime patches (AXPB blob); empty when Advance Mode is off. */
    val patchBlob: ByteArray = ByteArray(0),
)

/** Live statistics for the performance overlay and diagnostics. */
data class SessionStats(
    val fps: Double = 0.0,
    val speed: Double = 0.0,
    val frameCounter: Long = 0,
    val audioBufferedFrames: Int = 0,
    val audioUnderrunFrames: Long = 0,
    val audioSampleRate: Int = 0,
    val audioBackend: String = "",
    val fastForward: Boolean = false,
    val rewinding: Boolean = false,
    val rewindSeconds: Double = 0.0,
    val rewindBytes: Long = 0,
    val saveWriteFailed: Boolean = false,
    val videoDegraded: Boolean = false,
)

enum class PatchRuntimeState { PENDING, ACTIVE, APPLIED, SKIPPED, FAILED, DISABLED }

data class PatchRuntimeStatus(
    val id: String,
    val state: PatchRuntimeState,
    val applyCount: Long,
    val message: String,
)

/** Outcome of an operation that can fail with a user-facing message. */
sealed class OpResult {
    data object Ok : OpResult()
    data class Error(val message: String) : OpResult()

    val isOk: Boolean get() = this is Ok
}

/**
 * A running game. Implemented by the Android app on top of the native core;
 * the UI only ever talks to this interface.
 *
 * Methods marked *blocking* must not be called on the UI thread.
 */
interface EmulatorSession : AutoCloseable {
    /** Loads the game. Blocking. */
    fun open(request: LaunchRequest): OpResult
    fun start()
    fun setPaused(paused: Boolean)
    fun setKeys(mask: Int)
    fun setFastForward(active: Boolean, speed: FastForwardSpeed)
    fun setRewinding(active: Boolean)
    fun applySettings(settings: ResolvedSettings)

    /** Blocking. */
    fun saveState(file: File): OpResult
    /** Blocking. */
    fun loadState(file: File): OpResult
    /** Blocking. Persists the cartridge save now. */
    fun flushSave(): OpResult
    fun reset(): Boolean

    /** Copies the latest frame as ARGB_8888 pixels (240x160). */
    fun captureFrame(outArgb: IntArray): Boolean
    fun stats(): SessionStats
    fun patchStatus(): List<PatchRuntimeStatus>
    fun setPatchEnabled(id: String, enabled: Boolean): Boolean

    /** Releases everything. Writes pending saves first. Blocking. */
    override fun close()

    companion object {
        const val SCREEN_WIDTH = 240
        const val SCREEN_HEIGHT = 160
    }
}
