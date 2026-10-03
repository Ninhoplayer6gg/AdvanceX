// SPDX-License-Identifier: MPL-2.0
package io.advancex.core.emulator

/**
 * Hints the Advance Engine may contribute for a specific game (from its
 * profile). Only used when Advance Mode is on.
 */
data class EnhancementHints(
    val video: VideoSettings? = null,
)

/** Final settings for one play session, after every rule has been applied. */
data class ResolvedSettings(
    val advanceMode: Boolean,
    val video: VideoSettings,
    val audio: AudioSettings,
    val audioLowLatency: Boolean,
    val frameSkip: Int,
    val fastForward: FastForwardSpeed,
    val rewind: RewindSettings,
    val performance: PerformanceMode,
    /** Human-readable reasons when a requested setting was adjusted. */
    val notes: List<String>,
)

/**
 * Combines global settings, per-game overrides, Advance Engine hints and the
 * performance mode into the settings a session actually uses.
 *
 * Advance Mode policy:
 * - **OFF (Original GBA)**: the image is shown exactly as the console
 *   produced it. Only display choices that do not alter pixels are kept
 *   (scaling, nearest/sharp filtering, optional colour correction and frame
 *   blending, which emulate the original LCD). Shader effects, ambient
 *   backgrounds, profiles, patches and asset replacements are off.
 * - **ON (AdvanceX enhancements)**: user video settings apply, and a game
 *   profile may suggest presets.
 * - **Safe mode** (after a crash with enhancements) forces OFF.
 */
object SettingsResolver {
    fun resolve(
        global: EmulationSettings,
        overrides: GameOverrides?,
        hints: EnhancementHints? = null,
    ): ResolvedSettings {
        val notes = mutableListOf<String>()
        val requestedAdvance = overrides?.advanceMode ?: global.advanceModeDefault
        val safeMode = overrides?.safeMode == true
        val advance = requestedAdvance && !safeMode
        if (requestedAdvance && safeMode) {
            notes += "Enhancements are disabled for this game because a previous session crashed. " +
                "Re-enable Advance Mode in the game's settings when you want to try again."
        }

        val mode = overrides?.performance ?: global.performance
        val perf = PerformanceProfile.of(mode)

        var video = overrides?.video ?: global.video
        if (advance && hints?.video != null && overrides?.video == null) {
            video = hints.video
            notes += "Using the graphics preset suggested by this game's Advance profile."
        }

        if (!advance) {
            video = video.copy(
                background = BackgroundStyle.BLACK,
                scanlines = false,
                lcdGrid = false,
                sharpen = false,
                filter = if (video.filter == TextureFilter.BILINEAR) TextureFilter.SHARP_BILINEAR else video.filter,
            )
        }
        if (!perf.allowShaderEffects && video.usesShaderEffects) {
            video = video.copy(scanlines = false, lcdGrid = false, sharpen = false)
            notes += "Shader effects are off in ${mode.label} mode."
        }
        if (!perf.allowAmbientBackground && video.background == BackgroundStyle.AMBIENT) {
            video = video.copy(background = BackgroundStyle.BLACK)
        }

        val audio = global.audio.normalized().let { a ->
            if (a.latencyMs < perf.minAudioLatencyMs) a.copy(latencyMs = perf.minAudioLatencyMs) else a
        }

        var rewind = (overrides?.rewind ?: global.rewind).normalized()
        if (rewind.enabled && !perf.allowRewind) {
            rewind = rewind.copy(enabled = false)
            notes += "Rewind is off in ${mode.label} mode."
        }

        return ResolvedSettings(
            advanceMode = advance,
            video = video,
            audio = audio,
            audioLowLatency = perf.lowLatencyAudio,
            frameSkip = perf.frameSkip,
            fastForward = overrides?.fastForward ?: global.fastForward,
            rewind = rewind,
            performance = mode,
            notes = notes,
        )
    }
}
