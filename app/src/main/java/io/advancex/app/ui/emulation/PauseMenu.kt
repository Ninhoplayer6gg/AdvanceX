// SPDX-License-Identifier: MPL-2.0
package io.advancex.app.ui.emulation

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.ScrollView
import io.advancex.app.R
import io.advancex.app.ui.common.Format
import io.advancex.app.ui.common.Palette
import io.advancex.app.ui.common.TextStyle
import io.advancex.app.ui.common.add
import io.advancex.app.ui.common.card
import io.advancex.app.ui.common.choiceRow
import io.advancex.app.ui.common.dangerButton
import io.advancex.app.ui.common.dp
import io.advancex.app.ui.common.dpf
import io.advancex.app.ui.common.horizontal
import io.advancex.app.ui.common.primaryButton
import io.advancex.app.ui.common.roundedBackground
import io.advancex.app.ui.common.secondaryButton
import io.advancex.app.ui.common.switchRow
import io.advancex.app.ui.common.text
import io.advancex.app.ui.common.vertical
import io.advancex.core.emulator.FastForwardSpeed
import io.advancex.core.emulator.VideoSettings
import io.advancex.core.saves.StateSlot
import io.advancex.core.saves.StateSlotInfo
import io.advancex.engine.graphics.ShaderPresets

/**
 * In-game menu overlay (the game is paused while it is open). Shown as a side
 * panel in landscape and a bottom sheet in portrait.
 */
class PauseMenu(private val activity: EmulationActivity, private val host: FrameLayout, private val model: Model) {
    data class Model(
        val title: String,
        val advanceMode: Boolean,
        val ffSpeed: FastForwardSpeed,
        val rewindEnabled: Boolean,
        val touchVisible: Boolean,
        val currentVideo: VideoSettings,
    )

    private var overlay: View? = null

    fun show() = with(activity) { build(this) }

    private fun build(c: EmulationActivity) = with(c) {
        val landscape = host.width > host.height
        val panel = c.vertical {
            setPadding(c.dp(16), c.dp(18), c.dp(16), c.dp(18))
            background = roundedBackground(Palette.bg(context), c.dpf(24))
            addView(text(model.title, TextStyle.TITLE).apply { maxLines = 2 })
            add(text(c.getString(R.string.emu_menu_advance, c.getString(if (model.advanceMode) R.string.on else R.string.off)),
                TextStyle.CAPTION, if (model.advanceMode) Palette.cyan(context) else Palette.textSecondary(context)), bottomMarginDp = 14)

            add(primaryButton(c.getString(R.string.emu_menu_resume), R.drawable.ic_play) { activity.closeMenu() }, bottomMarginDp = 10)
            val stateRow = horizontal {
                add(secondaryButton(c.getString(R.string.emu_menu_save_state), R.drawable.ic_saves) { activity.menuSave() },
                    width = 0, weight = 1f, endMarginDp = 6)
                add(secondaryButton(c.getString(R.string.emu_menu_load_state), R.drawable.ic_reset) { activity.menuLoad() },
                    width = 0, weight = 1f, startMarginDp = 6)
            }
            add(stateRow, bottomMarginDp = 10)

            val speeds = FastForwardSpeed.entries
            add(choiceRow(c.getString(R.string.emu_menu_fast_forward), null, speeds.map { it.label }, speeds.indexOf(model.ffSpeed)) { i ->
                activity.menuSetSpeed(speeds[i])
            }, bottomMarginDp = 10)

            val presets = ShaderPresets.all
            val currentPreset = presets.indexOfFirst { it.video == model.currentVideo }
            add(choiceRow(c.getString(R.string.emu_menu_graphics), if (model.advanceMode) null else c.getString(R.string.graphics_effects_note),
                presets.map { it.name }, currentPreset) { i -> activity.menuSetVideo(presets[i].video) }, bottomMarginDp = 10)

            add(switchRow(c.getString(R.string.emu_menu_toggle_controls), null, model.touchVisible) { on -> activity.menuToggleTouch(on) },
                bottomMarginDp = 8)
            add(secondaryButton(c.getString(R.string.emu_menu_controls), R.drawable.ic_controls) { activity.menuEditControls() },
                bottomMarginDp = 8)
            val restartLabel = c.getString(R.string.emu_menu_restart_advance, c.getString(if (model.advanceMode) R.string.off else R.string.on))
            add(secondaryButton(restartLabel, R.drawable.ic_advance) { activity.menuRestartWithAdvance(!model.advanceMode) },
                bottomMarginDp = 8)
            add(secondaryButton(c.getString(R.string.emu_menu_reset), R.drawable.ic_reset) { activity.menuReset() }, bottomMarginDp = 8)
            addView(dangerButton(c.getString(R.string.emu_menu_quit)) { activity.quitGame() })
        }
        val scroll = ScrollView(c).apply {
            isVerticalScrollBarEnabled = false
            addView(panel)
        }
        val dim = FrameLayout(c).apply {
            setBackgroundColor(0xB3000000.toInt())
            isClickable = true
            setOnClickListener { activity.closeMenu() }
        }
        val params = if (landscape) {
            FrameLayout.LayoutParams(c.dp(380), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END).apply {
                setMargins(c.dp(12), c.dp(12), c.dp(12), c.dp(12))
            }
        } else {
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (host.height * 0.72f).toInt(), Gravity.BOTTOM).apply {
                setMargins(c.dp(8), 0, c.dp(8), c.dp(8))
            }
        }
        scroll.isClickable = true
        dim.addView(scroll, params)
        host.addView(dim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = dim
        panel.getChildAt(2)?.requestFocus()
        Unit
    }

    fun dismiss() {
        overlay?.let { host.removeView(it) }
        overlay = null
    }
}

/** Grid of save-state slots with thumbnails. */
class SlotPicker(
    private val activity: EmulationActivity,
    private val slots: List<StateSlotInfo>,
    private val saving: Boolean,
    private val onPick: (StateSlot) -> Unit,
) {
    fun show() = with(activity) {
        val c = this
        var dialog: AlertDialog? = null
        val grid = GridLayout(c).apply {
            columnCount = 3
            setPadding(c.dp(12), c.dp(8), c.dp(12), c.dp(8))
        }
        val usable = slots.filter { if (saving) !it.slot.isAuto else it.exists }
        for (info in usable) {
            val cell = c.card(onClick = {
                dialog?.dismiss()
                onPick(info.slot)
            }, paddingDp = 6) {
                val thumb = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = roundedBackground(Palette.surfaceHigh(context), c.dpf(8))
                    info.screenshot?.let { f ->
                        BitmapFactory.decodeFile(f.absolutePath)?.let { setImageDrawable(BitmapDrawable(c.resources, it).apply { isFilterBitmap = false }) }
                    }
                }
                add(thumb, height = c.dp(60))
                addView(text(info.slot.label, TextStyle.CAPTION, Palette.text(context)))
                addView(text(info.header?.let { Format.relative(it.timestampMs) } ?: c.getString(R.string.saves_slot_empty), TextStyle.CAPTION).apply {
                    textSize = 11f
                })
            }
            val params = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0
                setMargins(c.dp(4), c.dp(4), c.dp(4), c.dp(4))
            }
            grid.addView(cell, params)
        }
        val content: View = if (usable.isEmpty()) {
            text(c.getString(R.string.saves_slot_empty), TextStyle.BODY_SECONDARY).apply { setPadding(c.dp(24), c.dp(16), c.dp(24), c.dp(16)) }
        } else ScrollView(c).apply { addView(grid) }
        dialog = AlertDialog.Builder(c)
            .setTitle(if (saving) R.string.emu_menu_save_state else R.string.emu_menu_load_state)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        Unit
    }
}
