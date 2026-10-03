// SPDX-License-Identifier: MPL-2.0
package io.advancex.app.ui.common

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import io.advancex.app.ui.emulation.EmulationActivity
import io.advancex.app.ui.game.AdvanceModeActivity
import io.advancex.app.ui.game.GameDetailsActivity
import io.advancex.app.ui.game.SavesActivity
import io.advancex.app.ui.library.LibraryActivity
import io.advancex.app.ui.settings.ControlsActivity
import io.advancex.app.ui.settings.GraphicsActivity
import io.advancex.app.ui.settings.ModsActivity
import io.advancex.app.ui.settings.SettingsActivity
import io.advancex.app.ui.settings.StudioActivity
import io.advancex.app.ui.settings.TextViewerActivity

/** Navigation between screens. */
object Nav {
    const val EXTRA_GAME_ID = "gameId"
    const val EXTRA_STATE_SLOT = "stateSlot"
    const val EXTRA_RESUME_AUTO = "resumeAuto"
    const val EXTRA_TEXT_ASSET = "textAsset"
    const val EXTRA_TITLE = "title"

    fun library(c: Context) = c.startActivity(Intent(c, LibraryActivity::class.java))
    fun settings(c: Context) = c.startActivity(Intent(c, SettingsActivity::class.java))
    fun mods(c: Context, gameId: String? = null) = c.startActivity(Intent(c, ModsActivity::class.java).putExtra(EXTRA_GAME_ID, gameId))
    fun studio(c: Context) = c.startActivity(Intent(c, StudioActivity::class.java))
    fun controls(c: Context) = c.startActivity(Intent(c, ControlsActivity::class.java))
    fun graphics(c: Context, gameId: String? = null) =
        c.startActivity(Intent(c, GraphicsActivity::class.java).putExtra(EXTRA_GAME_ID, gameId))

    fun game(c: Context, gameId: String) = c.startActivity(Intent(c, GameDetailsActivity::class.java).putExtra(EXTRA_GAME_ID, gameId))
    fun advanceMode(c: Context, gameId: String) =
        c.startActivity(Intent(c, AdvanceModeActivity::class.java).putExtra(EXTRA_GAME_ID, gameId))
    fun saves(c: Context, gameId: String) = c.startActivity(Intent(c, SavesActivity::class.java).putExtra(EXTRA_GAME_ID, gameId))
    fun licenses(c: Context, title: String) = c.startActivity(
        Intent(c, TextViewerActivity::class.java).putExtra(EXTRA_TEXT_ASSET, "licenses/THIRD_PARTY_LICENSES.md").putExtra(EXTRA_TITLE, title),
    )

    /** Starts a game. [stateSlot] loads a save state; [resumeAuto] loads the autosave. */
    fun play(c: Context, gameId: String, stateSlot: Int = -1, resumeAuto: Boolean = false) =
        c.startActivity(
            Intent(c, EmulationActivity::class.java)
                .putExtra(EXTRA_GAME_ID, gameId)
                .putExtra(EXTRA_STATE_SLOT, stateSlot)
                .putExtra(EXTRA_RESUME_AUTO, resumeAuto)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
}

object Format {
    fun relative(ms: Long): String =
        if (ms <= 0) "never"
        else DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    fun duration(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> "< 1 min"
            minutes < 60 -> "$minutes min"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }

    fun bytes(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> "${n / 1024} KB"
        else -> String.format(java.util.Locale.US, "%.1f MB", n / (1024.0 * 1024.0))
    }

    fun dateTime(c: Context, ms: Long): String =
        DateUtils.formatDateTime(c, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
}
