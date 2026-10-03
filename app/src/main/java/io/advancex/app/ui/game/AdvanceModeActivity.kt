// SPDX-License-Identifier: MPL-2.0
package io.advancex.app.ui.game

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import io.advancex.app.R
import io.advancex.app.ui.common.BaseActivity
import io.advancex.app.ui.common.FlowLayout
import io.advancex.app.ui.common.Nav
import io.advancex.app.ui.common.Palette
import io.advancex.app.ui.common.TextStyle
import io.advancex.app.ui.common.add
import io.advancex.app.ui.common.card
import io.advancex.app.ui.common.chip
import io.advancex.app.ui.common.choiceRow
import io.advancex.app.ui.common.dp
import io.advancex.app.ui.common.dpf
import io.advancex.app.ui.common.gradientBackground
import io.advancex.app.ui.common.horizontal
import io.advancex.app.ui.common.icon
import io.advancex.app.ui.common.primaryButton
import io.advancex.app.ui.common.roundedBackground
import io.advancex.app.ui.common.sectionHeader
import io.advancex.app.ui.common.statusBadge
import io.advancex.app.ui.common.switchRow
import io.advancex.app.ui.common.text
import io.advancex.app.ui.common.vertical
import io.advancex.engine.EnginePlan
import io.advancex.engine.EngineRequest
import io.advancex.engine.content.ContentOrigin
import io.advancex.engine.graphics.FeatureStatus
import io.advancex.engine.patches.RuntimePatch
import io.advancex.engine.widescreen.WidescreenMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-game Advance Mode: Original GBA vs AdvanceX enhancements, the matched
 * profile, runtime patches, asset replacement and widescreen.
 */
class AdvanceModeActivity : BaseActivity() {
    private lateinit var gameId: String
    private lateinit var column: LinearLayout
    private var plan: EnginePlan? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gameId = intent.getStringExtra(Nav.EXTRA_GAME_ID) ?: return finish()
        column = contentColumn {}
        setScreen(getString(R.string.advance_title), column)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val game = services.library.get(gameId) ?: return finish()
        scope.launch {
            val config = services.gameConfigs.get(gameId)
            plan = withContext(Dispatchers.IO) {
                // Show what Advance Mode *would* do, with the user's patch choices.
                services.engine.plan(
                    EngineRequest(game.identity(), advanceMode = true, patchToggles = config.patchToggles,
                        replacementsEnabled = true, widescreen = config.widescreen),
                )
            }
            render()
        }
    }

    private fun render() {
        val config = services.gameConfigs.get(gameId)
        val global = services.settings.value.emulation
        val enabled = (config.overrides.advanceMode ?: global.advanceModeDefault) && !config.overrides.safeMode
        column.removeAllViews()

        if (config.overrides.safeMode) {
            column.add(card(color = 0x33FBBF24) {
                addView(text(getString(R.string.advance_safe_banner), TextStyle.BODY))
                add(primaryButton(getString(R.string.advance_safe_reenable), R.drawable.ic_shield) {
                    update { it.copy(overrides = it.overrides.copy(safeMode = false, advanceMode = true)) }
                }, topMarginDp = 12)
            }, bottomMarginDp = 12)
        }

        val modes = horizontal {
            add(modeCard(getString(R.string.advance_off_title), getString(R.string.advance_off_body), R.drawable.ic_controls, !enabled) {
                update { it.copy(overrides = it.overrides.copy(advanceMode = false)) }
            }, width = 0, weight = 1f, endMarginDp = 6)
            add(modeCard(getString(R.string.advance_on_title), getString(R.string.advance_on_body), R.drawable.ic_advance, enabled) {
                update { it.copy(overrides = it.overrides.copy(advanceMode = true, safeMode = false)) }
            }, width = 0, weight = 1f, startMarginDp = 6)
        }
        column.addView(modes)
        if (!enabled) {
            column.add(text(getString(R.string.advance_requires_on), TextStyle.CAPTION), topMarginDp = 12)
        }

        val p = plan ?: return
        val features = vertical { alpha = if (enabled) 1f else 0.55f }
        column.addView(features)

        // Profile
        features.addView(sectionHeader(getString(R.string.advance_profile)))
        val match = p.match
        features.addView(card {
            if (match == null) {
                addView(text(getString(R.string.game_profile_none), TextStyle.BODY_SECONDARY))
                add(text("Profiles are matched by ROM hash or game code. Mods and Advance Studio can add profiles.", TextStyle.CAPTION), topMarginDp = 6)
            } else {
                val profile = match.profile
                addView(text(profile.name, TextStyle.HEADLINE))
                val origin = when (val o = match.loaded.origin) {
                    ContentOrigin.Bundled -> "Bundled with AdvanceX"
                    ContentOrigin.User -> "Your profile"
                    is ContentOrigin.Mod -> "From mod: ${o.modId}"
                }
                add(text("$origin · ${match.confidence.description}", TextStyle.CAPTION), topMarginDp = 4)
                profile.description?.let { add(text(it, TextStyle.BODY_SECONDARY), topMarginDp = 8) }
                val chips = FlowLayout(context, dp(6))
                val e = profile.enhancements
                listOf("Shader" to e.shader, "Sprites" to e.spriteReplacement, "Patches" to e.runtimePatches,
                    "Widescreen" to e.widescreen, "Audio" to e.audioReplacement).filter { it.second }.forEach {
                    chips.addView(chip(it.first, Palette.cyan(context)))
                }
                add(chips, topMarginDp = 10)
            }
        })

        // Patches
        features.addView(sectionHeader(getString(R.string.advance_patches)))
        if (p.patches.isEmpty()) {
            features.addView(card { addView(text(getString(R.string.advance_patches_none), TextStyle.BODY_SECONDARY)) })
        }
        for (patch in p.patches) {
            val kind = if (patch is RuntimePatch.Rom) "ROM (virtual)" else "Memory"
            val subtitle = listOf(patch.description, kind).filter { it.isNotBlank() }.joinToString(" · ")
            features.add(switchRow(patch.name, subtitle, patch.enabled, enabled = enabled) { on ->
                update { it.copy(patchToggles = it.patchToggles + (patch.id to on)) }
            }, bottomMarginDp = 8)
        }

        // Replacements
        features.addView(sectionHeader(getString(R.string.advance_replacements)))
        features.add(switchRow(getString(R.string.advance_replacements),
            getString(R.string.advance_replacements_body, p.replacements.size), config.replacementsEnabled,
            badge = statusBadge(FeatureStatus.EXPERIMENTAL), enabled = enabled) { on ->
            update { it.copy(replacementsEnabled = on) }
        }, bottomMarginDp = 8)
        features.add(switchRow(getString(R.string.advance_auto_upscale), getString(R.string.advance_auto_upscale_body),
            config.autoUpscaleSprites, badge = statusBadge(FeatureStatus.EXPERIMENTAL), enabled = enabled) { on ->
            update { it.copy(autoUpscaleSprites = on) }
        })

        // Widescreen
        features.addView(sectionHeader(getString(R.string.advance_widescreen)))
        val modesList = WidescreenMode.entries
        features.addView(choiceRow(getString(R.string.advance_widescreen), getString(R.string.advance_widescreen_body),
            modesList.map { if (it.status == FeatureStatus.COMING_LATER) "${it.label} (${getString(R.string.badge_coming_later)})" else it.label },
            modesList.indexOf(config.widescreen)) { i ->
            update { it.copy(widescreen = modesList[i]) }
        })

        // Engine notes
        if (p.messages.isNotEmpty()) {
            features.addView(sectionHeader(getString(R.string.advance_messages)))
            features.addView(card {
                p.messages.forEach { m ->
                    addView(horizontal {
                        add(icon(if (m.warning) R.drawable.ic_info else R.drawable.ic_check,
                            if (m.warning) Palette.warning(context) else Palette.success(context), 18),
                            width = dp(18), height = dp(18), endMarginDp = 10)
                        add(text(m.text, TextStyle.CAPTION, Palette.text(context)), width = 0, weight = 1f)
                    }.apply { setPadding(0, dp(4), 0, dp(4)) })
                }
            })
        }
    }

    private fun modeCard(title: String, body: String, iconRes: Int, selected: Boolean, onSelect: () -> Unit) =
        card(onClick = onSelect, paddingDp = 16) {
            background = if (selected) {
                gradientBackground(intArrayOf((Palette.violet(context) and 0x00FFFFFF) or 0x55000000, Palette.surface(context)), dpf(18),
                    GradientDrawable.Orientation.TL_BR).apply { setStroke(dp(2), Palette.violet(context)) }
            } else {
                roundedBackground(Palette.surface(context), dpf(18), Palette.outline(context), dp(1))
            }
            addView(icon(iconRes, if (selected) Palette.cyan(context) else Palette.textSecondary(context), 28).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            })
            add(text(title, TextStyle.HEADLINE), topMarginDp = 10)
            add(text(body, TextStyle.CAPTION), topMarginDp = 4)
            if (selected) add(chip(getString(R.string.on), Palette.violet(context), filled = true),
                width = ViewGroup.LayoutParams.WRAP_CONTENT, topMarginDp = 10)
        }

    private fun update(transform: (io.advancex.app.data.GameConfig) -> io.advancex.app.data.GameConfig) {
        scope.launch {
            withContext(Dispatchers.IO) { services.gameConfigs.update(gameId, transform) }
            reload()
        }
    }
}
