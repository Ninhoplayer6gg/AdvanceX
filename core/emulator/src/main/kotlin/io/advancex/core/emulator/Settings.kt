// SPDX-License-Identifier: MPL-2.0
package io.advancex.core.emulator

import kotlinx.serialization.Serializable

@Serializable
enum class ScaleMode(val nativeId: Int, val label: String) {
    INTEGER(0, "Integer (pixel-perfect)"),
    FIT(1, "Fit screen (keep aspect)"),
    STRETCH(2, "Stretch (distorts)"),
}

@Serializable
enum class TextureFilter(val nativeId: Int, val label: String) {
    NEAREST(0, "Nearest (sharp pixels)"),
    BILINEAR(1, "Bilinear (smooth)"),
    SHARP_BILINEAR(2, "Sharp bilinear"),
}

@Serializable
enum class BackgroundStyle(val nativeId: Int, val label: String) {
    BLACK(0, "Black"),
    AMBIENT(1, "Ambient glow"),
}

/** Everything the native video pipeline needs. */
@Serializable
data class VideoSettings(
    val scaleMode: ScaleMode = ScaleMode.FIT,
    val filter: TextureFilter = TextureFilter.SHARP_BILINEAR,
    val background: BackgroundStyle = BackgroundStyle.BLACK,
    val colorCorrection: Boolean = false,
    val frameBlending: Boolean = false,
    val scanlines: Boolean = false,
    val lcdGrid: Boolean = false,
    val sharpen: Boolean = false,
    val scanlineStrength: Float = 0.30f,
    val gridStrength: Float = 0.35f,
    val sharpenStrength: Float = 0.50f,
    val frameBlendAmount: Float = 0.50f,
) {
    /** Layout expected by NativeBridge.setVideoConfig (ints). */
    fun nativeInts(): IntArray = intArrayOf(
        scaleMode.nativeId, filter.nativeId, background.nativeId,
        colorCorrection.int(), frameBlending.int(), scanlines.int(), lcdGrid.int(), sharpen.int(),
    )

    /** Layout expected by NativeBridge.setVideoConfig (floats). */
    fun nativeFloats(): FloatArray = floatArrayOf(scanlineStrength, gridStrength, sharpenStrength, frameBlendAmount)

    val usesShaderEffects: Boolean get() = scanlines || lcdGrid || sharpen

    private fun Boolean.int() = if (this) 1 else 0
}

@Serializable
data class AudioSettings(
    val volume: Float = 1f,
    val muted: Boolean = false,
    /** Target audio buffer in milliseconds (lower = less latency, more risk of crackle). */
    val latencyMs: Int = 64,
) {
    fun normalized() = copy(volume = volume.coerceIn(0f, 1f), latencyMs = latencyMs.coerceIn(20, 250))
}

@Serializable
enum class FastForwardSpeed(val multiplier: Int, val label: String) {
    X2(2, "2×"),
    X3(3, "3×"),
    X4(4, "4×"),
    UNLIMITED(0, "Unlimited"),
}

@Serializable
data class RewindSettings(
    val enabled: Boolean = false,
    /** Seconds of history to keep. */
    val durationSeconds: Int = 30,
    /** Memory budget for the history. */
    val maxMemoryMb: Int = 64,
    /** Snapshot every N frames (lower = smoother rewind, more CPU). */
    val intervalFrames: Int = 6,
) {
    fun normalized() = copy(
        durationSeconds = durationSeconds.coerceIn(5, 300),
        maxMemoryMb = maxMemoryMb.coerceIn(8, 512),
        intervalFrames = intervalFrames.coerceIn(1, 60),
    )
}

@Serializable
enum class PerformanceMode(val label: String, val description: String) {
    BALANCED("Balanced", "Full speed with efficient defaults. Recommended."),
    QUALITY("Quality", "Lowest audio latency and every visual effect allowed."),
    BATTERY_SAVER("Battery Saver", "Draws every other frame, larger audio buffer, no costly effects."),
}

/** Concrete knobs derived from a [PerformanceMode]. */
data class PerformanceProfile(
    val frameSkip: Int,
    val lowLatencyAudio: Boolean,
    val minAudioLatencyMs: Int,
    val allowShaderEffects: Boolean,
    val allowAmbientBackground: Boolean,
    val allowRewind: Boolean,
) {
    companion object {
        fun of(mode: PerformanceMode): PerformanceProfile = when (mode) {
            PerformanceMode.QUALITY -> PerformanceProfile(0, true, 20, true, true, true)
            PerformanceMode.BALANCED -> PerformanceProfile(0, true, 48, true, true, true)
            PerformanceMode.BATTERY_SAVER -> PerformanceProfile(1, false, 96, false, false, false)
        }
    }
}

/** Global emulation settings chosen by the user. */
@Serializable
data class EmulationSettings(
    val video: VideoSettings = VideoSettings(),
    val audio: AudioSettings = AudioSettings(),
    val performance: PerformanceMode = PerformanceMode.BALANCED,
    val fastForward: FastForwardSpeed = FastForwardSpeed.X3,
    val rewind: RewindSettings = RewindSettings(),
    /** Advance Mode for games without a per-game choice. */
    val advanceModeDefault: Boolean = false,
    val autoSaveStateOnExit: Boolean = true,
    val useUserBios: Boolean = false,
)

/** Per-game overrides; null fields inherit the global value. */
@Serializable
data class GameOverrides(
    val advanceMode: Boolean? = null,
    val video: VideoSettings? = null,
    val performance: PerformanceMode? = null,
    val fastForward: FastForwardSpeed? = null,
    val rewind: RewindSettings? = null,
    /** Forced off after a crash with enhancements on (see EnhancementGuard). */
    val safeMode: Boolean = false,
)
