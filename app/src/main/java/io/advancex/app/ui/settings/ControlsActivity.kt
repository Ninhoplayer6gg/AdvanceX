// SPDX-License-Identifier: MPL-2.0
package io.advancex.app.ui.settings

import android.app.AlertDialog
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.widget.EditText
import android.widget.LinearLayout
import io.advancex.app.R
import io.advancex.app.data.AppSettings
import io.advancex.app.ui.common.BaseActivity
import io.advancex.app.ui.common.TextStyle
import io.advancex.app.ui.common.add
import io.advancex.app.ui.common.card
import io.advancex.app.ui.common.dp
import io.advancex.app.ui.common.frame
import io.advancex.app.ui.common.secondaryButton
import io.advancex.app.ui.common.sectionHeader
import io.advancex.app.ui.common.settingRow
import io.advancex.app.ui.common.sliderRow
import io.advancex.app.ui.common.switchRow
import io.advancex.app.ui.common.text
import io.advancex.app.ui.controls.ControlsEditorActivity
import io.advancex.core.input.ControlId
import io.advancex.core.input.GbaButton
import io.advancex.core.input.Hotkey
import io.advancex.core.input.KeyAction
import io.advancex.core.input.KeyMap
import io.advancex.core.input.Orientation
import io.advancex.core.input.TouchLayout
import kotlin.math.roundToInt

class ControlsActivity : BaseActivity() {
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = contentColumn {}
        setScreen(getString(R.string.controls_title), column)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private val settings: AppSettings get() = services.settings.value

    private fun update(rerender: Boolean = true, transform: (AppSettings) -> AppSettings) {
        services.settings.update(transform)
        if (rerender) render()
    }

    /** Applies [transform] to both orientation layouts. */
    private fun updateLayouts(rerender: Boolean = true, transform: (TouchLayout) -> TouchLayout) =
        update(rerender) { it.copy(touchPortrait = transform(it.touchPortrait), touchLandscape = transform(it.touchLandscape)) }

    private fun render() {
        column.removeAllViews()
        val s = settings
        column.addView(sectionHeader(getString(R.string.controls_touch)))
        column.add(settingRow(getString(R.string.controls_edit_portrait), null, R.drawable.ic_controls) { edit(Orientation.PORTRAIT) },
            bottomMarginDp = 8)
        column.add(settingRow(getString(R.string.controls_edit_landscape), null, R.drawable.ic_screen_rotation) { edit(Orientation.LANDSCAPE) },
            bottomMarginDp = 8)
        column.add(sliderRow(getString(R.string.controls_opacity), 90, ((s.touchLandscape.opacity - 0.1f) * 100).roundToInt(),
            { "${it + 10}%" }) { v -> updateLayouts(false) { l -> l.copy(opacity = (v + 10) / 100f) } }, bottomMarginDp = 8)
        column.add(sliderRow(getString(R.string.controls_size), 120, ((s.touchLandscape.scale - 0.6f) * 100).roundToInt(),
            { "${it + 60}%" }) { v -> updateLayouts(false) { l -> l.copy(scale = (v + 60) / 100f) } }, bottomMarginDp = 8)
        column.add(switchRow(getString(R.string.controls_haptics), null, s.touchLandscape.haptics) { on ->
            updateLayouts { it.copy(haptics = on) }
        }, bottomMarginDp = 8)
        column.add(switchRow(getString(R.string.controls_hide_with_gamepad), null, s.hideTouchWithController) { on ->
            update { it.copy(hideTouchWithController = on) }
        }, bottomMarginDp = 8)
        for ((id, label) in listOf(ControlId.FAST_FORWARD to R.string.controls_show_ff, ControlId.REWIND to R.string.controls_show_rewind)) {
            val visible = s.touchLandscape.placement(id)?.visible ?: false
            column.add(switchRow(getString(label), null, visible) { on ->
                updateLayouts { l -> l.placement(id)?.let { p -> l.with(p.copy(visible = on)) } ?: l }
            }, bottomMarginDp = 8)
        }

        column.addView(sectionHeader(getString(R.string.controls_presets)))
        column.add(settingRow(getString(R.string.controls_save_preset), null, R.drawable.ic_add) { savePreset() }, bottomMarginDp = 8)
        for (preset in s.touchPresets.filter { it.orientation == Orientation.LANDSCAPE }) {
            column.add(settingRow(preset.name, null, R.drawable.ic_controls) { presetMenu(preset.name) }, bottomMarginDp = 8)
        }
        column.addView(settingRow(getString(R.string.controls_reset_layouts), null, R.drawable.ic_reset) {
            confirm(getString(R.string.controls_reset_layouts), getString(R.string.controls_touch), getString(R.string.action_reset)) {
                update { it.copy(touchPortrait = TouchLayout.DEFAULT_PORTRAIT, touchLandscape = TouchLayout.DEFAULT_LANDSCAPE) }
            }
        })

        column.addView(sectionHeader(getString(R.string.controls_connected)))
        val devices = connectedControllers()
        column.addView(card {
            if (devices.isEmpty()) addView(text(getString(R.string.controls_none_connected), TextStyle.BODY_SECONDARY))
            else devices.forEach { addView(text("• $it", TextStyle.BODY)) }
        })

        mappingSection(getString(R.string.controls_gamepad), s.gamepadMap, KeyMap.DEFAULT_GAMEPAD) { map -> update { it.copy(gamepadMap = map) } }
        mappingSection(getString(R.string.controls_keyboard), s.keyboardMap, KeyMap.DEFAULT_KEYBOARD) { map -> update { it.copy(keyboardMap = map) } }
    }

    private fun mappingSection(title: String, map: KeyMap, defaults: KeyMap, save: (KeyMap) -> Unit) {
        column.addView(sectionHeader(title))
        fun keysLabel(keys: List<Int>) =
            if (keys.isEmpty()) getString(R.string.controls_unassigned) else keys.joinToString(", ") { keyName(it) }
        for (b in GbaButton.entries) {
            column.add(settingRow(b.label, keysLabel(map.keysFor(b))) {
                captureKey(b.label) { code -> save(map.bind(code, KeyAction(button = b))) }
            }, bottomMarginDp = 6)
        }
        for (h in Hotkey.entries) {
            column.add(settingRow(h.label, keysLabel(map.keysFor(h))) {
                captureKey(h.label) { code -> save(map.bind(code, KeyAction(hotkey = h))) }
            }, bottomMarginDp = 6)
        }
        column.add(secondaryButton(getString(R.string.controls_reset_mapping), R.drawable.ic_reset) { save(defaults) }, topMarginDp = 4)
    }

    private fun captureKey(label: String, onKey: (Int) -> Unit) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(label)
            .setMessage(getString(R.string.controls_press_key, label))
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnKeyListener { d, keyCode, event ->
            if (event.action != KeyEvent.ACTION_UP) return@setOnKeyListener true
            if (keyCode == KeyEvent.KEYCODE_BACK && event.source and InputDevice.SOURCE_GAMEPAD == 0) {
                d.dismiss()
                return@setOnKeyListener true
            }
            onKey(keyCode)
            d.dismiss()
            true
        }
        dialog.show()
    }

    private fun keyName(code: Int): String =
        KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace("BUTTON_", "").replace('_', ' ')

    private fun connectedControllers(): List<String> {
        val im = getSystemService(InputManager::class.java)
        return im.inputDeviceIds.toList().mapNotNull { InputDevice.getDevice(it) }.filter { d ->
            !d.isVirtual && (d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                d.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
                d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC)
        }.map { it.name }.distinct()
    }

    private fun edit(orientation: Orientation) {
        startActivity(Intent(this, ControlsEditorActivity::class.java).putExtra(ControlsEditorActivity.EXTRA_ORIENTATION, orientation.name))
    }

    private fun savePreset() {
        val input = EditText(this).apply {
            hint = getString(R.string.controls_preset_name)
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.controls_save_preset)
            .setView(frame { setPadding(dp(20), dp(8), dp(20), 0); addView(input) })
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Preset ${settings.touchPresets.size / 2 + 1}" }.take(40)
                update { s ->
                    val others = s.touchPresets.filterNot { it.name == name }
                    s.copy(touchPresets = others + s.touchPortrait.copy(name = name) + s.touchLandscape.copy(name = name))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun presetMenu(name: String) {
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(arrayOf("Apply", getString(R.string.action_delete))) { _, which ->
                update { s ->
                    val presets = s.touchPresets.filter { it.name == name }
                    if (which == 0) {
                        var next = s
                        presets.forEach { next = next.withTouchLayout(it.normalized()) }
                        next
                    } else {
                        s.copy(touchPresets = s.touchPresets.filterNot { it.name == name })
                    }
                }
            }
            .show()
    }
}
