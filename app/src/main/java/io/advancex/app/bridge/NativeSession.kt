// SPDX-License-Identifier: MPL-2.0
package io.advancex.app.bridge

import android.view.Surface
import io.advancex.core.emulator.EmulatorSession
import io.advancex.core.emulator.FastForwardSpeed
import io.advancex.core.emulator.LaunchRequest
import io.advancex.core.emulator.OpResult
import io.advancex.core.emulator.PatchRuntimeState
import io.advancex.core.emulator.PatchRuntimeStatus
import io.advancex.core.emulator.ResolvedSettings
import io.advancex.core.emulator.SessionStats
import io.advancex.engine.replacements.ArgbImage
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * [EmulatorSession] backed by the native runtime (mGBA core + Advance Engine
 * native side + GL/audio outputs). Thread-safe: the native layer serializes
 * everything that touches the core on its emulation thread.
 */
class NativeSession(private val audioBackend: Int = 0) : EmulatorSession {
    // Calls hold the read lock; close() takes the write lock, so the native
    // object can never be destroyed while another thread is inside a call.
    private val lock = ReentrantReadWriteLock()
    private var handle: Long = NativeBridge.create()

    private inline fun <T> withHandle(default: T, block: (Long) -> T): T = lock.read {
        val h = handle
        if (h == 0L) default else block(h)
    }

    override fun open(request: LaunchRequest): OpResult = withHandle(OpResult.Error("Session closed")) { h ->
        val error = NativeBridge.open(
            h,
            request.romFile.absolutePath,
            request.cartridgeSave.absolutePath,
            request.biosFile?.absolutePath ?: "",
            request.gameId,
            request.settings.advanceMode,
            audioBackend,
            request.patchBlob,
        )
        if (error != null) return OpResult.Error(error)
        applySettings(request.settings)
        OpResult.Ok
    }

    override fun start() = withHandle(Unit) { NativeBridge.start(it) }
    override fun setPaused(paused: Boolean) = withHandle(Unit) { NativeBridge.setPaused(it, paused) }
    override fun setKeys(mask: Int) = withHandle(Unit) { NativeBridge.setKeys(it, mask) }
    override fun setFastForward(active: Boolean, speed: FastForwardSpeed) =
        withHandle(Unit) { NativeBridge.setFastForward(it, active, speed.multiplier) }

    override fun setRewinding(active: Boolean) = withHandle(Unit) { NativeBridge.setRewinding(it, active) }

    override fun applySettings(settings: ResolvedSettings) = withHandle(Unit) { h ->
        NativeBridge.setVideoConfig(h, settings.video.nativeInts(), settings.video.nativeFloats())
        val a = settings.audio
        NativeBridge.setAudioSettings(h, a.volume, a.muted, a.latencyMs, settings.audioLowLatency)
        NativeBridge.setFrameSkip(h, settings.frameSkip)
        val r = settings.rewind
        NativeBridge.setRewindConfig(h, r.enabled, r.intervalFrames, r.durationSeconds, r.maxMemoryMb)
    }

    fun setVideo(settings: io.advancex.core.emulator.VideoSettings) =
        withHandle(Unit) { NativeBridge.setVideoConfig(it, settings.nativeInts(), settings.nativeFloats()) }

    override fun saveState(file: File): OpResult = withHandle(OpResult.Error("Session closed")) { h ->
        file.parentFile?.mkdirs()
        NativeBridge.saveState(h, file.absolutePath)?.let { OpResult.Error(it) } ?: OpResult.Ok
    }

    override fun loadState(file: File): OpResult = withHandle(OpResult.Error("Session closed")) { h ->
        NativeBridge.loadState(h, file.absolutePath)?.let { OpResult.Error(it) } ?: OpResult.Ok
    }

    override fun flushSave(): OpResult = withHandle(OpResult.Ok) { h ->
        NativeBridge.flushSave(h)?.let { OpResult.Error(it) } ?: OpResult.Ok
    }

    override fun reset(): Boolean = withHandle(false) { NativeBridge.reset(it) }

    override fun captureFrame(outArgb: IntArray): Boolean = withHandle(false) { NativeBridge.captureFrame(it, outArgb) }

    override fun stats(): SessionStats = withHandle(SessionStats()) { h ->
        val v = DoubleArray(12)
        val backend = NativeBridge.getStats(h, v)
        SessionStats(
            fps = v[0], speed = v[1], frameCounter = v[2].toLong(), audioBufferedFrames = v[3].toInt(),
            audioUnderrunFrames = v[4].toLong(), audioSampleRate = v[5].toInt(), audioBackend = backend,
            fastForward = v[6] != 0.0, rewinding = v[7] != 0.0, rewindSeconds = v[8], rewindBytes = v[9].toLong(),
            saveWriteFailed = v[10] != 0.0, videoDegraded = v[11] != 0.0,
        )
    }

    override fun patchStatus(): List<PatchRuntimeStatus> = withHandle(emptyList()) { h ->
        NativeBridge.getPatchStatus(h).mapNotNull { line ->
            val parts = line.split('\t', limit = 4)
            if (parts.size < 4) return@mapNotNull null
            PatchRuntimeStatus(
                id = parts[0],
                state = PatchRuntimeState.entries.getOrElse(parts[1].toIntOrNull() ?: 0) { PatchRuntimeState.PENDING },
                applyCount = parts[2].toLongOrNull() ?: 0,
                message = parts[3],
            )
        }
    }

    override fun setPatchEnabled(id: String, enabled: Boolean): Boolean =
        withHandle(false) { NativeBridge.setPatchEnabled(it, id, enabled) }

    // --- Android-specific extensions -------------------------------------------------

    fun setSurface(surface: Surface?) = withHandle(Unit) { NativeBridge.setSurface(it, surface) }

    fun setReplacementKeys(keys: LongArray) = withHandle(Unit) { NativeBridge.setReplacementKeys(it, keys) }

    fun addReplacementTexture(key: Long, image: ArgbImage) =
        withHandle(Unit) { NativeBridge.addReplacementTexture(it, key, image.pixels, image.width, image.height) }

    fun clearReplacementTextures() = withHandle(Unit) { NativeBridge.clearReplacementTextures(it) }

    fun setAssetDump(enabled: Boolean) = withHandle(Unit) { NativeBridge.setAssetDump(it, enabled) }

    /** Next sprite captured by the asset dump, or null. */
    fun pollDumpedSprite(): Pair<Long, ArgbImage>? = withHandle(null) { h ->
        val pixels = IntArray(64 * 64)
        val size = IntArray(2)
        val key = NativeBridge.pollDumpedSprite(h, pixels, size)
        if (key == 0L || size[0] <= 0 || size[1] <= 0) null else key to ArgbImage(size[0], size[1], pixels)
    }

    fun readMemory(address: Long, size: Int): ByteArray? = withHandle(null) { NativeBridge.readMemory(it, address.toInt(), size) }

    override fun close() = lock.write {
        val h = handle
        if (h != 0L) {
            handle = 0
            NativeBridge.destroy(h)
        }
    }
}
