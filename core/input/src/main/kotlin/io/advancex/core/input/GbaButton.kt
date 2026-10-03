// SPDX-License-Identifier: MPL-2.0
package io.advancex.core.input

/** GBA buttons. [mask] matches the hardware KEYINPUT bit order used by the core. */
enum class GbaButton(val mask: Int, val label: String) {
    A(1 shl 0, "A"),
    B(1 shl 1, "B"),
    SELECT(1 shl 2, "Select"),
    START(1 shl 3, "Start"),
    RIGHT(1 shl 4, "Right"),
    LEFT(1 shl 5, "Left"),
    UP(1 shl 6, "Up"),
    DOWN(1 shl 7, "Down"),
    R(1 shl 8, "R"),
    L(1 shl 9, "L");

    companion object {
        const val DPAD_MASK = 0xF0
        const val ALL_MASK = 0x3FF

        fun fromMask(mask: Int): Set<GbaButton> = entries.filterTo(mutableSetOf()) { mask and it.mask != 0 }

        /**
         * Removes physically impossible combinations (left+right, up+down)
         * that some games mishandle; the most recent direction wins.
         */
        fun sanitize(mask: Int, preferLeft: Boolean = false, preferUp: Boolean = false): Int {
            var m = mask and ALL_MASK
            if (m and LEFT.mask != 0 && m and RIGHT.mask != 0) {
                m = m and (if (preferLeft) RIGHT.mask else LEFT.mask).inv()
            }
            if (m and UP.mask != 0 && m and DOWN.mask != 0) {
                m = m and (if (preferUp) DOWN.mask else UP.mask).inv()
            }
            return m
        }
    }
}

/** Emulator actions that are not GBA buttons. */
enum class Hotkey(val label: String) {
    FAST_FORWARD_HOLD("Fast-forward (hold)"),
    FAST_FORWARD_TOGGLE("Fast-forward (toggle)"),
    REWIND_HOLD("Rewind (hold)"),
    QUICK_SAVE("Quick save"),
    QUICK_LOAD("Quick load"),
    MENU("Open menu"),
}
