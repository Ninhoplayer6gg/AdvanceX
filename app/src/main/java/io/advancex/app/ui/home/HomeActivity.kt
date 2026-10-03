// SPDX-License-Identifier: MPL-2.0
package io.advancex.app.ui.home

import android.content.Intent
import android.graphics.LinearGradient
import android.graphics.Shader
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import io.advancex.app.BuildInfo
import io.advancex.app.R
import io.advancex.app.data.GameEntry
import io.advancex.app.data.ImportResult
import io.advancex.app.ui.common.BaseActivity
import io.advancex.app.ui.common.CoverView
import io.advancex.app.ui.common.Format
import io.advancex.app.ui.common.ImportFlow
import io.advancex.app.ui.common.Nav
import io.advancex.app.ui.common.Palette
import io.advancex.app.ui.common.TextStyle
import io.advancex.app.ui.common.add
import io.advancex.app.ui.common.card
import io.advancex.app.ui.common.dp
import io.advancex.app.ui.common.horizontal
import io.advancex.app.ui.common.icon
import io.advancex.app.ui.common.iconButton
import io.advancex.app.ui.common.primaryButton
import io.advancex.app.ui.common.secondaryButton
import io.advancex.app.ui.common.sectionHeader
import io.advancex.app.ui.common.text
import io.advancex.app.ui.common.vertical

/**
 * Home: ADVANCEX header, Continue Playing, Recently Played, Game Library,
 * Import Game and Settings.
 */
class HomeActivity : BaseActivity() {
    private lateinit var column: LinearLayout
    private val importFlow by lazy { ImportFlow(this) { onImported(it) } }
    private val libraryListener: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = contentColumn {}
        val scroll = ScrollView(this).apply { addView(column) }
        val root = vertical { setBackgroundColor(Palette.bg(context)); add(scroll, height = 0, weight = 1f) }
        applySystemBarInsets(root)
        setContentView(root)
        services.library.addListener(libraryListener)
    }

    override fun onResume() {
        super.onResume()
        render()
        services.startupNotice?.let { notice ->
            services.startupNotice = null
            showMessage(getString(R.string.notice_title), notice)
        }
    }

    override fun onDestroy() {
        services.library.removeListener(libraryListener)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!importFlow.onActivityResult(requestCode, resultCode, data)) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun onImported(results: List<ImportResult>) {
        val added = results.filterIsInstance<ImportResult.Added>()
        if (added.size == 1) Nav.game(this, added[0].entry.id)
    }

    private fun render() {
        column.removeAllViews()
        column.addView(header())
        val games = services.library.games
        val recent = services.library.recentlyPlayed(10)

        column.addView(sectionHeader(getString(R.string.home_continue_playing)))
        column.addView(
            when {
                recent.isNotEmpty() -> continueCard(recent.first())
                games.isEmpty() -> welcomeCard()
                else -> card {
                    addView(text(getString(R.string.home_pick_game), TextStyle.BODY_SECONDARY))
                    add(primaryButton(getString(R.string.home_library), R.drawable.ic_library) { Nav.library(context) }, topMarginDp = 16)
                }
            },
        )

        if (recent.size > 1) {
            column.addView(sectionHeader(getString(R.string.home_recently_played)))
            column.addView(recentRow(recent.drop(1)))
        }

        column.add(quickActions(games.size), topMarginDp = 20)
        column.add(
            text(getString(R.string.home_footer, BuildInfo.VERSION_NAME), TextStyle.CAPTION, Palette.textTertiary(this)).apply {
                gravity = Gravity.CENTER
            },
            topMarginDp = 28,
        )
    }

    private fun header(): View = horizontal {
        setPadding(dp(4), dp(20), 0, dp(8))
        val titles = vertical {
            val word = text("ADVANCEX", TextStyle.DISPLAY)
            word.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
                (v as android.widget.TextView).paint.shader = LinearGradient(
                    0f, 0f, (r - l).toFloat(), 0f, Palette.violet(context), Palette.cyan(context), Shader.TileMode.CLAMP,
                )
            }
            addView(word)
            addView(text(getString(R.string.tagline), TextStyle.CAPTION))
        }
        add(titles, width = 0, weight = 1f)
        addView(iconButton(R.drawable.ic_settings, getString(R.string.action_settings)) { Nav.settings(context) })
    }

    private fun continueCard(game: GameEntry): View = card(onClick = { Nav.game(this, game.id) }, paddingDp = 0) {
        val cover = CoverView(context).apply {
            aspect = 0.5f
            bind(game, services.library.coverFile(game))
        }
        addView(cover)
        val info = vertical {
            setPadding(dp(16), dp(14), dp(16), dp(16))
            addView(text(getString(R.string.home_last_played, Format.relative(game.lastPlayedAtMs), Format.duration(game.playTimeMs)),
                TextStyle.CAPTION))
            val buttons = horizontal {
                add(primaryButton(getString(R.string.action_continue), R.drawable.ic_play) {
                    val hasAuto = services.saveLayout.stateFile(game.id, io.advancex.core.saves.StateSlot.AUTO).isFile
                    Nav.play(context, game.id, resumeAuto = hasAuto)
                }, width = 0, weight = 1f)
                add(secondaryButton(getString(R.string.action_details)) { Nav.game(context, game.id) },
                    width = ViewGroup.LayoutParams.WRAP_CONTENT, startMarginDp = 12)
            }
            add(buttons, topMarginDp = 12)
        }
        addView(info)
    }

    private fun welcomeCard(): View = card(paddingDp = 20) {
        addView(icon(R.drawable.ic_advance, Palette.violet(context), 40).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        })
        add(text(getString(R.string.home_welcome_title), TextStyle.TITLE), topMarginDp = 12)
        add(text(getString(R.string.home_welcome_body), TextStyle.BODY_SECONDARY), topMarginDp = 8)
        add(primaryButton(getString(R.string.action_import), R.drawable.ic_import) { importFlow.start() }, topMarginDp = 20)
        add(secondaryButton(getString(R.string.home_try_demo), R.drawable.ic_play) { importFlow.installDemo() }, topMarginDp = 10)
    }

    private fun recentRow(games: List<GameEntry>): View {
        val row = horizontal { setPadding(0, 0, dp(8), 0) }
        for (g in games) {
            val item = vertical {
                isClickable = true
                setOnClickListener { Nav.game(context, g.id) }
                addView(CoverView(context).apply {
                    aspect = 1.0f
                    showTitle = true
                    bind(g, services.library.coverFile(g))
                }, LinearLayout.LayoutParams(dp(132), dp(132)))
                add(text(Format.relative(g.lastPlayedAtMs), TextStyle.CAPTION).apply { setPadding(dp(4), dp(6), 0, 0) })
            }
            row.add(item, width = dp(132), endMarginDp = 12)
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun quickActions(count: Int): View {
        val grid = GridLayout(this).apply { columnCount = 2 }
        fun tile(iconRes: Int, title: String, subtitle: String, action: () -> Unit) {
            val t = card(onClick = action, paddingDp = 16) {
                addView(icon(iconRes, Palette.violet(context), 28).apply { layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)) })
                add(text(title, TextStyle.HEADLINE), topMarginDp = 12)
                add(text(subtitle, TextStyle.CAPTION), topMarginDp = 2)
            }
            val params = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0
                setMargins(dp(5), dp(5), dp(5), dp(5))
            }
            grid.addView(t, params)
        }
        tile(R.drawable.ic_library, getString(R.string.home_library), getString(R.string.home_games_count, count)) { Nav.library(this) }
        tile(R.drawable.ic_import, getString(R.string.action_import), getString(R.string.home_import_hint)) { importFlow.start() }
        tile(R.drawable.ic_mods, getString(R.string.home_mods), getString(R.string.home_mods_hint)) { Nav.mods(this) }
        tile(R.drawable.ic_settings, getString(R.string.action_settings), getString(R.string.home_settings_hint)) { Nav.settings(this) }
        return grid
    }
}
