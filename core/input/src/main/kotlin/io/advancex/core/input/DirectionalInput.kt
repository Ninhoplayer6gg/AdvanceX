// SPDX-License-Identifier: MPL-2.0
package io.advancex.core.input

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Converts a 2D vector (touch offset from the D-pad centre, or an analog
 * stick) into GBA direction bits using 8 equal 45° sectors, so diagonals are
 * as easy to hit as cardinal directions.
 */
object DirectionalInput {
    /**
     * @param dx horizontal offset (positive = right)
     * @param dy vertical offset (positive = down, screen coordinates)
     * @param deadZone magnitude below which no direction is pressed
     */
    fun toMask(dx: Float, dy: Float, deadZone: Float): Int {
        if (hypot(dx, dy) < deadZone) return 0
        // Angle in degrees, 0 = right, counter-clockwise positive (flip y).
        var angle = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble()))
        if (angle < 0) angle += 360.0
        val sector = (((angle + 22.5) % 360.0) / 45.0).toInt()
        return when (sector) {
            0 -> GbaButton.RIGHT.mask
            1 -> GbaButton.RIGHT.mask or GbaButton.UP.mask
            2 -> GbaButton.UP.mask
            3 -> GbaButton.UP.mask or GbaButton.LEFT.mask
            4 -> GbaButton.LEFT.mask
            5 -> GbaButton.LEFT.mask or GbaButton.DOWN.mask
            6 -> GbaButton.DOWN.mask
            else -> GbaButton.DOWN.mask or GbaButton.RIGHT.mask
        }
    }

    /** Analog stick / HAT axes in -1..1 (y positive = down, as Android reports it). */
    fun fromAxes(x: Float, y: Float, threshold: Float = 0.45f): Int = toMask(x, y, threshold)
}
