// SPDX-License-Identifier: MPL-2.0
package io.advancex.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InputTest {
    @Test
    fun buttonMasksMatchHardwareOrder() {
        assertEquals(0x001, GbaButton.A.mask)
        assertEquals(0x200, GbaButton.L.mask)
        assertEquals(setOf(GbaButton.A, GbaButton.START), GbaButton.fromMask(0x009))
    }

    @Test
    fun oppositeDirectionsAreSanitized() {
        val both = GbaButton.LEFT.mask or GbaButton.RIGHT.mask or GbaButton.A.mask
        assertEquals(GbaButton.RIGHT.mask or GbaButton.A.mask, GbaButton.sanitize(both))
        assertEquals(GbaButton.LEFT.mask or GbaButton.A.mask, GbaButton.sanitize(both, preferLeft = true))
    }

    @Test
    fun dpadHasEightEqualSectorsAndDeadZone() {
        assertEquals(0, DirectionalInput.toMask(2f, 2f, deadZone = 10f))
        assertEquals(GbaButton.RIGHT.mask, DirectionalInput.toMask(50f, 5f, 10f))
        assertEquals(GbaButton.UP.mask, DirectionalInput.toMask(3f, -50f, 10f))
        assertEquals(GbaButton.DOWN.mask or GbaButton.LEFT.mask, DirectionalInput.toMask(-40f, 40f, 10f))
        assertEquals(GbaButton.UP.mask or GbaButton.RIGHT.mask, DirectionalInput.toMask(40f, -38f, 10f))
        // Sectors are ±22.5° wide: 30° above horizontal is already the diagonal.
        assertEquals(GbaButton.RIGHT.mask or GbaButton.UP.mask, DirectionalInput.toMask(50f, -29f, 10f))
        assertEquals(GbaButton.RIGHT.mask, DirectionalInput.toMask(50f, -20f, 10f))
        assertEquals(GbaButton.LEFT.mask, DirectionalInput.fromAxes(-1f, 0.1f))
    }

    @Test
    fun inputMixerCombinesSourcesIndependently() {
        val mixer = InputMixer()
        mixer.press(InputMixer.TOUCH, GbaButton.A)
        mixer.press(InputMixer.GAMEPAD, GbaButton.A)
        mixer.release(InputMixer.TOUCH, GbaButton.A)
        assertEquals(GbaButton.A.mask, mixer.mask())
        mixer.set(InputMixer.STICK, GbaButton.LEFT.mask)
        mixer.set(InputMixer.KEYBOARD, GbaButton.RIGHT.mask)
        assertEquals(GbaButton.A.mask or GbaButton.RIGHT.mask, mixer.mask())
        mixer.clear()
        assertEquals(0, mixer.mask())
    }

    @Test
    fun defaultMapsCoverEveryButton() {
        for (map in listOf(KeyMap.DEFAULT_GAMEPAD, KeyMap.DEFAULT_KEYBOARD)) {
            for (button in GbaButton.entries) {
                assertTrue(map.keysFor(button).isNotEmpty(), "$button unmapped")
            }
            assertTrue(map.keysFor(Hotkey.FAST_FORWARD_HOLD).isNotEmpty())
        }
        val remapped = KeyMap.DEFAULT_KEYBOARD.bind(KeyCodes.W, KeyAction(button = GbaButton.UP))
        assertEquals(GbaButton.UP, remapped.actionFor(KeyCodes.W)?.button)
        assertFailsWith<IllegalArgumentException> { KeyAction() }
    }

    @Test
    fun touchLayoutsSerializeAndAreClamped() {
        val layout = TouchLayout.DEFAULT_PORTRAIT
            .with(ControlPlacement(ControlId.A, 1.5f, -1f, 10f))
        val a = assertNotNull(layout.placement(ControlId.A))
        assertEquals(1f, a.x)
        assertEquals(0f, a.y)
        assertEquals(ControlPlacement.MIN_SIZE_DP, a.sizeDp)

        val json = layout.copy(opacity = 5f).toJson()
        val back = TouchLayout.fromJson(json)
        assertEquals(1f, back.opacity)
        assertEquals(ControlId.entries.size, back.controls.size)

        // Unknown fields from newer versions are ignored; missing controls restored.
        val partial = """{"name":"Mine","orientation":"LANDSCAPE","controls":[],"future":1}"""
        val restored = TouchLayout.fromJson(partial)
        assertEquals(ControlId.entries.size, restored.controls.size)
        assertEquals("Mine", restored.name)
    }
}
