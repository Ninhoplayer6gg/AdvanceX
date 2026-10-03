// SPDX-License-Identifier: MPL-2.0
package io.advancex.app.ui.settings

import android.os.Bundle
import android.widget.LinearLayout
import io.advancex.app.R
import io.advancex.app.ui.common.BaseActivity
import io.advancex.app.ui.common.Nav
import io.advancex.app.ui.common.TextStyle
import io.advancex.app.ui.common.add
import io.advancex.app.ui.common.choiceRow
import io.advancex.app.ui.common.sectionHeader
import io.advancex.app.ui.common.sliderRow
import io.advancex.app.ui.common.switchRow
import io.advancex.app.ui.common.text
import io.advancex.app.ui.common.vertical
import io.advancex.core.emulator.BackgroundStyle
import io.advancex.core.emulator.ScaleMode
import io.advancex.core.emulator.TextureFilter
import io.advancex.core.emulator.VideoSettings
import io.advancex.engine.graphics.ShaderPresets
import kotlin.math.roundToInt

/**
 * Video pipeline settings. Opened with a game id it edits that game's
 * override (or lets the game follow the global settings).
 */
class GraphicsActivity : BaseActivity() {
    private var gameId: String? = null
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gameId = intent.getStringExtra(Nav.EXTRA_GAME_ID)
        column = contentColumn {}
        val title = gameId?.let { services.library.get(it)?.title }?.let { "${getString(R.string.graphics_title)} · $it" }
            ?: getString(R.string.graphics_title)
        setScreen(title, column)
        render()
    }

    private fun currentVideo(): VideoSettings {
        val id = gameId
        val global = services.settings.value.emulation.video
        return if (id == null) global else services.gameConfigs.get(id).overrides.video ?: global
    }

    private fun usesGlobal(): Boolean = gameId?.let { services.gameConfigs.get(it).overrides.video == null } ?: false

    private fun store(video: VideoSettings?, rerender: Boolean = true) {
        val id = gameId
        if (id == null) {
            services.settings.update { it.copy(emulation = it.emulation.copy(video = video ?: VideoSettings())) }
        } else {
            services.gameConfigs.update(id) { it.copy(overrides = it.overrides.copy(video = video)) }
        }
        if (rerender) render()
    }

    private fun edit(rerender: Boolean = true, transform: (VideoSettings) -> VideoSettings) = store(transform(currentVideo()), rerender)

    private fun render() {
        column.removeAllViews()
        val v = currentVideo()
        if (gameId != null) {
            column.add(switchRow(getString(R.string.graphics_use_global), null, usesGlobal()) { useGlobal ->
                store(if (useGlobal) null else services.settings.value.emulation.video)
            }, bottomMarginDp = 4)
        }
        val editable = !usesGlobal()
        val body = vertical { alpha = if (editable) 1f else 0.5f; isEnabled = editable }
        column.addView(body)

        body.addView(sectionHeader(getString(R.string.graphics_presets)))
        val presets = ShaderPresets.all
        val current = presets.indexOfFirst { it.video == v }
        body.addView(choiceRow(getString(R.string.graphics_presets), presets.getOrNull(current)?.description,
            presets.map { it.name }, current) { i -> if (editable) store(presets[i].video) })

        body.addView(sectionHeader(getString(R.string.settings_video)))
        val scales = ScaleMode.entries
        body.add(choiceRow(getString(R.string.graphics_scaling), null, scales.map { it.label }, scales.indexOf(v.scaleMode)) { i ->
            if (editable) edit { it.copy(scaleMode = scales[i]) }
        }, bottomMarginDp = 8)
        val filters = TextureFilter.entries
        body.add(choiceRow(getString(R.string.graphics_filter), null, filters.map { it.label }, filters.indexOf(v.filter)) { i ->
            if (editable) edit { it.copy(filter = filters[i]) }
        }, bottomMarginDp = 8)
        body.add(switchRow(getString(R.string.graphics_color_correction), getString(R.string.graphics_color_correction_body),
            v.colorCorrection, enabled = editable) { on -> edit { it.copy(colorCorrection = on) } }, bottomMarginDp = 8)
        body.add(switchRow(getString(R.string.graphics_frame_blending), getString(R.string.graphics_frame_blending_body),
            v.frameBlending, enabled = editable) { on -> edit { it.copy(frameBlending = on) } })

        body.addView(sectionHeader(getString(R.string.graphics_effects)))
        body.add(text(getString(R.string.graphics_effects_note), TextStyle.CAPTION), bottomMarginDp = 8)
        body.add(switchRow(getString(R.string.graphics_scanlines), null, v.scanlines, enabled = editable) { on ->
            edit { it.copy(scanlines = on) }
        }, bottomMarginDp = 8)
        if (v.scanlines) body.add(strength(v.scanlineStrength) { s -> edit(false) { it.copy(scanlineStrength = s) } }, bottomMarginDp = 8)
        body.add(switchRow(getString(R.string.graphics_lcd_grid), null, v.lcdGrid, enabled = editable) { on ->
            edit { it.copy(lcdGrid = on) }
        }, bottomMarginDp = 8)
        if (v.lcdGrid) body.add(strength(v.gridStrength) { s -> edit(false) { it.copy(gridStrength = s) } }, bottomMarginDp = 8)
        body.add(switchRow(getString(R.string.graphics_sharpen), null, v.sharpen, enabled = editable) { on ->
            edit { it.copy(sharpen = on) }
        }, bottomMarginDp = 8)
        if (v.sharpen) body.add(strength(v.sharpenStrength) { s -> edit(false) { it.copy(sharpenStrength = s) } }, bottomMarginDp = 8)
        body.add(switchRow(getString(R.string.graphics_ambient), getString(R.string.graphics_ambient_body),
            v.background == BackgroundStyle.AMBIENT, enabled = editable) { on ->
            edit { it.copy(background = if (on) BackgroundStyle.AMBIENT else BackgroundStyle.BLACK) }
        })
    }

    private fun strength(value: Float, onChange: (Float) -> Unit) =
        sliderRow(getString(R.string.graphics_strength), 100, (value * 100).roundToInt(), { "$it%" }) { onChange(it / 100f) }
}
