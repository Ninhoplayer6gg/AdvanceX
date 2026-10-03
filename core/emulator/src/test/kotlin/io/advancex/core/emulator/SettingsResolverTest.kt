// SPDX-License-Identifier: MPL-2.0
package io.advancex.core.emulator

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsResolverTest {
    private val fancyVideo = VideoSettings(
        filter = TextureFilter.BILINEAR,
        background = BackgroundStyle.AMBIENT,
        colorCorrection = true,
        scanlines = true,
        lcdGrid = true,
        sharpen = true,
    )

    @Test
    fun originalModeStripsEnhancementsButKeepsFidelityOptions() {
        val global = EmulationSettings(video = fancyVideo, advanceModeDefault = false)
        val r = SettingsResolver.resolve(global, null)
        assertFalse(r.advanceMode)
        assertFalse(r.video.scanlines || r.video.lcdGrid || r.video.sharpen)
        assertEquals(BackgroundStyle.BLACK, r.video.background)
        assertEquals(TextureFilter.SHARP_BILINEAR, r.video.filter)
        assertTrue(r.video.colorCorrection) // emulates the original LCD: allowed
    }

    @Test
    fun advanceModeKeepsEffectsAndUsesProfileHints() {
        val global = EmulationSettings(video = fancyVideo, advanceModeDefault = true)
        val r = SettingsResolver.resolve(global, null)
        assertTrue(r.advanceMode)
        assertTrue(r.video.scanlines && r.video.lcdGrid && r.video.sharpen)
        assertEquals(BackgroundStyle.AMBIENT, r.video.background)

        val hint = VideoSettings(lcdGrid = true, colorCorrection = true)
        val hinted = SettingsResolver.resolve(global, GameOverrides(advanceMode = true), EnhancementHints(hint))
        assertEquals(hint, hinted.video)
        // An explicit per-game video choice beats the profile suggestion.
        val own = SettingsResolver.resolve(global, GameOverrides(video = fancyVideo), EnhancementHints(hint))
        assertEquals(fancyVideo, own.video)
    }

    @Test
    fun safeModeForcesOriginal() {
        val global = EmulationSettings(video = fancyVideo, advanceModeDefault = true)
        val r = SettingsResolver.resolve(global, GameOverrides(advanceMode = true, safeMode = true))
        assertFalse(r.advanceMode)
        assertTrue(r.notes.any { "crashed" in it })
    }

    @Test
    fun batterySaverLimitsCostlyFeatures() {
        val global = EmulationSettings(
            video = fancyVideo,
            advanceModeDefault = true,
            performance = PerformanceMode.BATTERY_SAVER,
            rewind = RewindSettings(enabled = true),
            audio = AudioSettings(latencyMs = 30),
        )
        val r = SettingsResolver.resolve(global, null)
        assertEquals(1, r.frameSkip)
        assertFalse(r.audioLowLatency)
        assertEquals(96, r.audio.latencyMs)
        assertFalse(r.video.scanlines || r.video.lcdGrid || r.video.sharpen)
        assertEquals(BackgroundStyle.BLACK, r.video.background)
        assertFalse(r.rewind.enabled)
        assertTrue(r.notes.isNotEmpty())
    }

    @Test
    fun nativeVideoLayoutIsStable() {
        val v = VideoSettings(ScaleMode.INTEGER, TextureFilter.NEAREST, BackgroundStyle.AMBIENT, true, false, true)
        assertContentEquals(intArrayOf(0, 0, 1, 1, 0, 1, 0, 0), v.nativeInts())
        assertEquals(4, v.nativeFloats().size)
    }
}
