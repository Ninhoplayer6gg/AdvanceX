// SPDX-License-Identifier: MPL-2.0
package io.advancex.engine.graphics

import io.advancex.core.emulator.BackgroundStyle
import io.advancex.core.emulator.ScaleMode
import io.advancex.core.emulator.TextureFilter
import io.advancex.core.emulator.VideoSettings

/** Availability label shown in the UI next to a feature. */
enum class FeatureStatus(val label: String) {
    STABLE(""),
    EXPERIMENTAL("Experimental"),
    COMING_LATER("Coming later"),
}

/** A named combination of video pipeline settings. */
data class ShaderPreset(
    val id: String,
    val name: String,
    val description: String,
    val video: VideoSettings,
    val status: FeatureStatus = FeatureStatus.STABLE,
)

/**
 * Built-in shader presets. All of them are implemented by the native uber
 * shader (no custom GLSL yet). Loading user/mod GLSL shaders is planned for
 * the shader manager in 0.2 ("Coming later").
 */
object ShaderPresets {
    val ORIGINAL = ShaderPreset(
        "original", "Original", "Unfiltered pixels exactly as the console drew them.",
        VideoSettings(scaleMode = ScaleMode.INTEGER, filter = TextureFilter.NEAREST),
    )
    val CRISP = ShaderPreset(
        "crisp", "Crisp", "Sharp pixels that fill the screen without shimmering.",
        VideoSettings(scaleMode = ScaleMode.FIT, filter = TextureFilter.SHARP_BILINEAR),
    )
    val LCD = ShaderPreset(
        "lcd", "GBA LCD", "Pixel grid, original screen colours and motion persistence.",
        VideoSettings(
            scaleMode = ScaleMode.FIT, filter = TextureFilter.SHARP_BILINEAR,
            colorCorrection = true, frameBlending = true, lcdGrid = true, gridStrength = 0.35f,
        ),
    )
    val SCANLINES = ShaderPreset(
        "scanlines", "Scanlines", "Retro horizontal scanlines.",
        VideoSettings(scaleMode = ScaleMode.FIT, filter = TextureFilter.SHARP_BILINEAR, scanlines = true),
    )
    val SMOOTH = ShaderPreset(
        "smooth", "Smooth", "Soft bilinear filtering with light sharpening.",
        VideoSettings(scaleMode = ScaleMode.FIT, filter = TextureFilter.BILINEAR, sharpen = true, sharpenStrength = 0.4f),
    )
    val CINEMA = ShaderPreset(
        "cinema", "Cinema", "Crisp image with an ambient glow filling the sides of the screen.",
        VideoSettings(scaleMode = ScaleMode.FIT, filter = TextureFilter.SHARP_BILINEAR, background = BackgroundStyle.AMBIENT),
    )

    val all: List<ShaderPreset> = listOf(ORIGINAL, CRISP, LCD, SCANLINES, SMOOTH, CINEMA)

    fun byId(id: String?): ShaderPreset? = all.firstOrNull { it.id == id }
}
