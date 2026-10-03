// SPDX-License-Identifier: MPL-2.0
package io.advancex.app.ui.game

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import io.advancex.app.R
import io.advancex.app.data.GameEntry
import io.advancex.app.ui.common.BaseActivity
import io.advancex.app.ui.common.CoverView
import io.advancex.app.ui.common.Format
import io.advancex.app.ui.common.Nav
import io.advancex.app.ui.common.Palette
import io.advancex.app.ui.common.TextStyle
import io.advancex.app.ui.common.add
import io.advancex.app.ui.common.chip
import io.advancex.app.ui.common.dp
import io.advancex.app.ui.common.horizontal
import io.advancex.app.ui.common.iconButton
import io.advancex.app.ui.common.primaryButton
import io.advancex.app.ui.common.secondaryButton
import io.advancex.app.ui.common.settingRow
import io.advancex.app.ui.common.text
import io.advancex.app.ui.common.vertical
import io.advancex.core.saves.StateSlot
import io.advancex.engine.EngineRequest
import io.advancex.engine.mods.Compatibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Game page: Play, Advance Mode, Mods, Graphics, Controls, Saves, Game Information. */
class GameDetailsActivity : BaseActivity() {
    private lateinit var gameId: String
    private lateinit var column: LinearLayout
    private var profileName: String? = null
    private var compatibleMods = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gameId = intent.getStringExtra(Nav.EXTRA_GAME_ID) ?: return finish()
        column = contentColumn {}
        val game = services.library.get(gameId) ?: return finish()
        val favorite = iconButton(if (game.favorite) R.drawable.ic_star_filled else R.drawable.ic_star,
            getString(R.string.library_favorite_add)) { toggleFavorite() }
        setScreen(null, column, actions = listOf(favorite))
    }

    override fun onResume() {
        super.onResume()
        val game = services.library.get(gameId) ?: return finish()
        render(game)
        scope.launch {
            val info = withContext(Dispatchers.IO) {
                val identity = game.identity()
                val plan = services.engine.plan(EngineRequest(identity, advanceMode = false))
                val mods = services.mods.installed().count { services.mods.compatibility(it, identity) == Compatibility.COMPATIBLE }
                plan.match?.profile?.name to mods
            }
            profileName = info.first
            compatibleMods = info.second
            services.library.get(gameId)?.let { render(it) }
        }
    }

    private fun toggleFavorite() {
        scope.launch(Dispatchers.IO) { services.library.update(gameId) { it.copy(favorite = !it.favorite) } }
        recreate()
    }

    private fun render(game: GameEntry) {
        column.removeAllViews()
        column.addView(CoverView(this).apply {
            aspect = 0.62f
            bind(game, services.library.coverFile(game))
        })
        column.add(text(game.title, TextStyle.TITLE), topMarginDp = 16)
        val chips = horizontal {
            if (game.gameCode.isNotBlank()) add(chip(game.gameCode, Palette.cyan(context)), width = ViewGroup.LayoutParams.WRAP_CONTENT, endMarginDp = 8)
            add(chip(game.region, Palette.violet(context)), width = ViewGroup.LayoutParams.WRAP_CONTENT, endMarginDp = 8)
            add(chip(Format.bytes(game.size), Palette.textSecondary(context)), width = ViewGroup.LayoutParams.WRAP_CONTENT, endMarginDp = 8)
            if (game.isDemo) add(chip("Homebrew", Palette.success(context)), width = ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        column.add(chips, topMarginDp = 8)
        val played = if (game.lastPlayedAtMs > 0) getString(R.string.home_last_played, Format.relative(game.lastPlayedAtMs), Format.duration(game.playTimeMs))
        else getString(R.string.library_never_played)
        column.add(text(played, TextStyle.CAPTION), topMarginDp = 8)

        val hasAuto = services.saveLayout.stateFile(game.id, StateSlot.AUTO).isFile
        if (hasAuto) {
            column.add(primaryButton(getString(R.string.game_resume), R.drawable.ic_play) { Nav.play(this, game.id, resumeAuto = true) }, topMarginDp = 20)
            column.add(secondaryButton(getString(R.string.game_start_fresh)) { Nav.play(this, game.id) }, topMarginDp = 10)
        } else {
            column.add(primaryButton(getString(R.string.game_play), R.drawable.ic_play) { Nav.play(this, game.id) }, topMarginDp = 20)
        }

        val config = services.gameConfigs.get(game.id)
        val global = services.settings.value.emulation
        val advanceOn = (config.overrides.advanceMode ?: global.advanceModeDefault) && !config.overrides.safeMode
        val advanceSubtitle = buildString {
            append(
                when {
                    config.overrides.safeMode -> getString(R.string.game_advance_safe)
                    advanceOn -> getString(R.string.game_advance_on)
                    else -> getString(R.string.game_advance_off)
                },
            )
            append('\n')
            append(profileName?.let { getString(R.string.game_profile_detected, it) } ?: getString(R.string.game_profile_none))
        }
        val advanceSwitch = Switch(this).apply {
            isChecked = advanceOn
            thumbTintList = ColorStateList.valueOf(if (advanceOn) Palette.violet(context) else Palette.textSecondary(context))
            setOnCheckedChangeListener { _, checked ->
                scope.launch(Dispatchers.IO) {
                    services.gameConfigs.update(game.id) {
                        it.copy(overrides = it.overrides.copy(advanceMode = checked, safeMode = if (checked) false else it.overrides.safeMode))
                    }
                    withContext(Dispatchers.Main) { services.library.get(gameId)?.let { g -> render(g) } }
                }
            }
        }
        column.add(settingRow(getString(R.string.game_advance_mode), advanceSubtitle, R.drawable.ic_advance, advanceSwitch) {
            Nav.advanceMode(this, game.id)
        }, topMarginDp = 24)

        val modsSubtitle = if (compatibleMods == 0) getString(R.string.game_mods_none) else getString(R.string.game_mods_count, compatibleMods)
        column.add(settingRow(getString(R.string.game_mods), modsSubtitle, R.drawable.ic_mods) { Nav.mods(this, game.id) }, topMarginDp = 8)
        val graphicsSubtitle = if (config.overrides.video != null) getString(R.string.game_graphics_custom) else getString(R.string.game_graphics_global)
        column.add(settingRow(getString(R.string.game_graphics), graphicsSubtitle, R.drawable.ic_graphics) { Nav.graphics(this, game.id) }, topMarginDp = 8)
        column.add(settingRow(getString(R.string.game_controls), getString(R.string.game_controls_hint), R.drawable.ic_controls) { Nav.controls(this) }, topMarginDp = 8)
        column.add(settingRow(getString(R.string.game_saves), savesSummary(game), R.drawable.ic_saves) { Nav.saves(this, game.id) }, topMarginDp = 8)
        column.add(settingRow(getString(R.string.game_info), "${game.headerTitle.ifBlank { "—" }} · CRC32 ${game.crc32}", R.drawable.ic_info) {
            showInfo(game)
        }, topMarginDp = 8)
    }

    private fun savesSummary(game: GameEntry): String {
        val states = services.saves.slots(game.id).count { it.exists }
        val cart = services.saveLayout.cartridgeSave(game.id)
        if (states == 0 && !cart.isFile) return getString(R.string.game_saves_none)
        return getString(R.string.game_saves_summary, states, if (cart.isFile) Format.bytes(cart.length()) else "—")
    }

    private fun showInfo(game: GameEntry) {
        val rows = listOf(
            "Title" to game.title,
            "Header title" to game.headerTitle.ifBlank { "—" },
            "Game code" to game.gameCode.ifBlank { "—" },
            "Maker code" to game.makerCode.ifBlank { "—" },
            "Revision" to game.version.toString(),
            "Region" to game.region,
            "Size" to "${Format.bytes(game.size)} (${game.size} bytes)",
            "CRC32" to game.crc32,
            "SHA-1" to game.sha1,
            "SHA-256" to game.sha256,
            "Game ID" to game.id,
            "Added" to Format.dateTime(this, game.addedAtMs),
            "Play time" to Format.duration(game.playTimeMs),
            "Advance profile" to (profileName ?: "None"),
        )
        val content = vertical {
            setPadding(dp(24), dp(8), dp(24), dp(8))
            rows.forEach { (k, v) ->
                addView(text(k, TextStyle.LABEL).apply { setPadding(0, dp(10), 0, 0) })
                addView(text(v, TextStyle.BODY).apply {
                    setTextIsSelectable(true)
                    if (k.startsWith("SHA") || k == "CRC32") typeface = Typeface.MONOSPACE
                })
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.game_info)
            .setView(android.widget.ScrollView(this).apply { addView(content) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

}
