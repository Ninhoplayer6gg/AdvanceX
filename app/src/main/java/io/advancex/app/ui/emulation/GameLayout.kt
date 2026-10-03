// SPDX-License-Identifier: MPL-2.0
package io.advancex.app.ui.emulation

import android.graphics.Rect
import io.advancex.core.input.Orientation

/**
 * Screen geometry shared by the game screen and the controls editor, so the
 * editor shows controls exactly where they will appear while playing.
 *
 * - Portrait: the game sits at the top (3:2, full width); controls get the
 *   area below it.
 * - Landscape: the game uses the whole screen; controls overlay it.
 */
object GameLayout {
    data class Areas(val game: Rect, val controls: Rect)

    fun orientationOf(width: Int, height: Int): Orientation =
        if (height > width) Orientation.PORTRAIT else Orientation.LANDSCAPE

    fun compute(width: Int, height: Int, insetTop: Int, insetBottom: Int, insetLeft: Int, insetRight: Int): Areas {
        return if (orientationOf(width, height) == Orientation.PORTRAIT) {
            val gameHeight = (width * 2) / 3
            val game = Rect(0, insetTop, width, insetTop + gameHeight)
            Areas(game, Rect(insetLeft, game.bottom, width - insetRight, height - insetBottom))
        } else {
            Areas(Rect(0, 0, width, height), Rect(insetLeft, insetTop, width - insetRight, height - insetBottom))
        }
    }
}
