// SPDX-License-Identifier: MPL-2.0
package io.advancex.app.bridge

import android.view.Surface

/**
 * JNI entry points into libadvancex.so (native/jni/advancex_jni.cpp).
 * Methods are bound with RegisterNatives, so names/signatures here must
 * match the native method table exactly.
 *
 * Use [NativeSession] rather than calling these directly.
 */
object NativeBridge {
    init {
        System.loadLibrary("advancex")
    }

    @JvmStatic external fun init(logPath: String, crashReportPath: String): Boolean
    @JvmStatic external fun version(): String
    @JvmStatic external fun create(): Long
    @JvmStatic external fun destroy(handle: Long)

    /** Returns null on success, or a user-facing error message. */
    @JvmStatic external fun open(
        handle: Long, romPath: String, savePath: String, biosPath: String, gameId: String,
        advanceMode: Boolean, audioBackend: Int, patchBlob: ByteArray,
    ): String?

    @JvmStatic external fun start(handle: Long)
    @JvmStatic external fun setPaused(handle: Long, paused: Boolean)
    @JvmStatic external fun setKeys(handle: Long, keys: Int)
    @JvmStatic external fun setFastForward(handle: Long, active: Boolean, multiplier: Int)
    @JvmStatic external fun setRewinding(handle: Long, active: Boolean)
    @JvmStatic external fun setRewindConfig(handle: Long, enabled: Boolean, intervalFrames: Int, maxSeconds: Int, maxMegabytes: Int)
    @JvmStatic external fun setAudioSettings(handle: Long, volume: Float, muted: Boolean, latencyMs: Int, lowLatency: Boolean)
    @JvmStatic external fun setFrameSkip(handle: Long, frames: Int)
    @JvmStatic external fun setVideoConfig(handle: Long, ints: IntArray, floats: FloatArray)
    @JvmStatic external fun setSurface(handle: Long, surface: Surface?)
    @JvmStatic external fun saveState(handle: Long, path: String): String?
    @JvmStatic external fun loadState(handle: Long, path: String): String?
    @JvmStatic external fun reset(handle: Long): Boolean
    @JvmStatic external fun flushSave(handle: Long): String?
    @JvmStatic external fun captureFrame(handle: Long, outArgb: IntArray): Boolean
    /** Fills [out] (12 values) and returns the audio backend name. */
    @JvmStatic external fun getStats(handle: Long, out: DoubleArray): String
    /** Each entry: "id\tstate\tapplyCount\tmessage". */
    @JvmStatic external fun getPatchStatus(handle: Long): Array<String>
    @JvmStatic external fun setPatchEnabled(handle: Long, id: String, enabled: Boolean): Boolean
    @JvmStatic external fun setReplacementKeys(handle: Long, keys: LongArray)
    @JvmStatic external fun addReplacementTexture(handle: Long, key: Long, argb: IntArray, width: Int, height: Int)
    @JvmStatic external fun clearReplacementTextures(handle: Long)
    @JvmStatic external fun setAssetDump(handle: Long, enabled: Boolean)
    /** Returns the sprite key (0 = none) and fills pixels + [width, height]. */
    @JvmStatic external fun pollDumpedSprite(handle: Long, outArgb: IntArray, outSize: IntArray): Long
    @JvmStatic external fun readMemory(handle: Long, address: Int, size: Int): ByteArray?
}
