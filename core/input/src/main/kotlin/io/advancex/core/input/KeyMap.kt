// SPDX-License-Identifier: MPL-2.0
package io.advancex.core.input

import kotlinx.serialization.Serializable

/**
 * Android `KeyEvent` key codes used by the default mappings. Duplicated here
 * as plain constants so this module stays free of Android dependencies.
 */
object KeyCodes {
    const val BACK = 4
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val A = 29
    const val D = 32
    const val Q = 45
    const val S = 47
    const val W = 51
    const val X = 52
    const val Z = 54
    const val TAB = 61
    const val SPACE = 62
    const val ENTER = 66
    const val DEL = 67
    const val BUTTON_A = 96
    const val BUTTON_B = 97
    const val BUTTON_X = 99
    const val BUTTON_Y = 100
    const val BUTTON_L1 = 102
    const val BUTTON_R1 = 103
    const val BUTTON_L2 = 104
    const val BUTTON_R2 = 105
    const val BUTTON_START = 108
    const val BUTTON_SELECT = 109
    const val BUTTON_MODE = 110
    const val ESCAPE = 111
    const val F1 = 131
    const val F2 = 132
    const val F4 = 134
}

/** What a physical key does. Exactly one of [button] / [hotkey] is set. */
@Serializable
data class KeyAction(val button: GbaButton? = null, val hotkey: Hotkey? = null) {
    init {
        require((button == null) != (hotkey == null)) { "KeyAction needs exactly one target" }
    }
}

/**
 * Mapping from physical keys (Android key codes) to GBA buttons and
 * hotkeys. Separate maps exist for gamepads and keyboards because their
 * natural layouts differ.
 */
@Serializable
data class KeyMap(val bindings: Map<Int, KeyAction>) {
    fun actionFor(keyCode: Int): KeyAction? = bindings[keyCode]

    fun keysFor(button: GbaButton): List<Int> = bindings.filterValues { it.button == button }.keys.sorted()
    fun keysFor(hotkey: Hotkey): List<Int> = bindings.filterValues { it.hotkey == hotkey }.keys.sorted()

    /** Returns a copy where [keyCode] maps to [action] (replacing any previous use of that key). */
    fun bind(keyCode: Int, action: KeyAction): KeyMap = KeyMap(bindings + (keyCode to action))

    /** Returns a copy without any key bound to [button]. */
    fun unbind(button: GbaButton): KeyMap = KeyMap(bindings.filterValues { it.button != button })

    companion object {
        private fun b(button: GbaButton) = KeyAction(button = button)
        private fun h(hotkey: Hotkey) = KeyAction(hotkey = hotkey)

        /** Xbox/PlayStation-style controllers: physical positions match the GBA (B left of A). */
        val DEFAULT_GAMEPAD = KeyMap(
            mapOf(
                KeyCodes.BUTTON_A to b(GbaButton.B),
                KeyCodes.BUTTON_B to b(GbaButton.A),
                KeyCodes.BUTTON_X to b(GbaButton.B),
                KeyCodes.BUTTON_Y to b(GbaButton.A),
                KeyCodes.BUTTON_L1 to b(GbaButton.L),
                KeyCodes.BUTTON_R1 to b(GbaButton.R),
                KeyCodes.BUTTON_START to b(GbaButton.START),
                KeyCodes.BUTTON_SELECT to b(GbaButton.SELECT),
                KeyCodes.DPAD_UP to b(GbaButton.UP),
                KeyCodes.DPAD_DOWN to b(GbaButton.DOWN),
                KeyCodes.DPAD_LEFT to b(GbaButton.LEFT),
                KeyCodes.DPAD_RIGHT to b(GbaButton.RIGHT),
                KeyCodes.BUTTON_R2 to h(Hotkey.FAST_FORWARD_HOLD),
                KeyCodes.BUTTON_L2 to h(Hotkey.REWIND_HOLD),
                KeyCodes.BUTTON_MODE to h(Hotkey.MENU),
            ),
        )

        val DEFAULT_KEYBOARD = KeyMap(
            mapOf(
                KeyCodes.X to b(GbaButton.A),
                KeyCodes.Z to b(GbaButton.B),
                KeyCodes.A to b(GbaButton.L),
                KeyCodes.S to b(GbaButton.R),
                KeyCodes.ENTER to b(GbaButton.START),
                KeyCodes.DEL to b(GbaButton.SELECT),
                KeyCodes.DPAD_UP to b(GbaButton.UP),
                KeyCodes.DPAD_DOWN to b(GbaButton.DOWN),
                KeyCodes.DPAD_LEFT to b(GbaButton.LEFT),
                KeyCodes.DPAD_RIGHT to b(GbaButton.RIGHT),
                KeyCodes.SPACE to h(Hotkey.FAST_FORWARD_HOLD),
                KeyCodes.TAB to h(Hotkey.FAST_FORWARD_TOGGLE),
                KeyCodes.Q to h(Hotkey.REWIND_HOLD),
                KeyCodes.F1 to h(Hotkey.QUICK_SAVE),
                KeyCodes.F4 to h(Hotkey.QUICK_LOAD),
                KeyCodes.ESCAPE to h(Hotkey.MENU),
            ),
        )
    }
}

/**
 * Accumulates key presses from several sources (touch, gamepad, keyboard)
 * into one GBA key mask. Each source updates only its own bits, so lifting a
 * finger never releases a button that is held on a controller.
 */
class InputMixer {
    private val sources = IntArray(SOURCE_COUNT)

    fun set(source: Int, mask: Int) {
        sources[source] = mask and GbaButton.ALL_MASK
    }

    fun press(source: Int, button: GbaButton) {
        sources[source] = sources[source] or button.mask
    }

    fun release(source: Int, button: GbaButton) {
        sources[source] = sources[source] and button.mask.inv()
    }

    fun clear() = sources.fill(0)

    /** Combined, sanitized mask to send to the core. */
    fun mask(): Int {
        var m = 0
        for (s in sources) m = m or s
        return GbaButton.sanitize(m)
    }

    companion object {
        const val TOUCH = 0
        const val GAMEPAD = 1
        const val KEYBOARD = 2
        const val STICK = 3
        const val SOURCE_COUNT = 4
    }
}
