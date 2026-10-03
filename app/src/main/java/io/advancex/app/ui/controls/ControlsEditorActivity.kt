// SPDX-License-Identifier: MPL-2.0
package io.advancex.app.ui.controls

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import io.advancex.app.R
import io.advancex.app.ui.common.BaseActivity
import io.advancex.app.ui.common.TextStyle
import io.advancex.app.ui.common.add
import io.advancex.app.ui.common.dp
import io.advancex.app.ui.common.dpf
import io.advancex.app.ui.common.horizontal
import io.advancex.app.ui.common.primaryButton
import io.advancex.app.ui.common.roundedBackground
import io.advancex.app.ui.common.secondaryButton
import io.advancex.app.ui.common.text
import io.advancex.app.ui.common.vertical
import io.advancex.app.ui.emulation.GameLayout
import io.advancex.app.ui.emulation.TouchControlsView
import io.advancex.core.input.Orientation
import io.advancex.core.input.TouchLayout

/**
 * Touch layout editor. Shows the controls exactly where they appear in game
 * (same geometry as EmulationActivity); drag to move, select to resize/hide.
 */
class ControlsEditorActivity : BaseActivity() {
    private lateinit var editOrientation: Orientation
    private lateinit var root: FrameLayout
    private lateinit var controls: TouchControlsView
    private lateinit var game: View
    private var working: TouchLayout = TouchLayout.DEFAULT_LANDSCAPE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editOrientation = runCatching { Orientation.valueOf(intent.getStringExtra(EXTRA_ORIENTATION) ?: "") }.getOrDefault(Orientation.LANDSCAPE)
        requestedOrientation = if (editOrientation == Orientation.PORTRAIT) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        working = services.settings.value.touchLayout(editOrientation).normalized()

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        game = vertical {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1B1F3A.toInt(), 0xFF0E1124.toInt()))
            addView(text("Game screen · 240×160", TextStyle.CAPTION).apply { gravity = Gravity.CENTER })
        }
        controls = TouchControlsView(this).apply {
            layoutModel = working
            editMode = true
            onLayoutEdited = { working = it }
        }
        val toolbar = vertical {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(0xE6151827.toInt(), dpf(20))
            addView(text(getString(R.string.editor_hint), TextStyle.CAPTION).apply { gravity = Gravity.CENTER })
            val row = horizontal {
                gravity = Gravity.CENTER
                add(secondaryButton(getString(R.string.editor_smaller)) { controls.resizeSelected(-8f) },
                    width = ViewGroup.LayoutParams.WRAP_CONTENT, endMarginDp = 6)
                add(secondaryButton(getString(R.string.editor_bigger)) { controls.resizeSelected(8f) },
                    width = ViewGroup.LayoutParams.WRAP_CONTENT, endMarginDp = 6)
                add(secondaryButton(getString(R.string.editor_toggle_visibility)) { controls.toggleSelectedVisibility() },
                    width = ViewGroup.LayoutParams.WRAP_CONTENT, endMarginDp = 6)
                add(secondaryButton(getString(R.string.action_reset)) {
                    working = TouchLayout.defaultFor(editOrientation).copy(opacity = working.opacity, scale = working.scale, haptics = working.haptics)
                    controls.layoutModel = working
                }, width = ViewGroup.LayoutParams.WRAP_CONTENT, endMarginDp = 6)
                add(primaryButton(getString(R.string.action_done), R.drawable.ic_check) { save() },
                    width = ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            add(row, topMarginDp = 8)
        }
        root.addView(game)
        root.addView(controls)
        root.addView(toolbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or (if (editOrientation == Orientation.PORTRAIT) Gravity.TOP else Gravity.CENTER_VERTICAL)).apply {
            topMargin = dp(16)
        })
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> root.post { place() } }
        setContentView(root)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.hide(WindowInsets.Type.systemBars())
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private var lastSize = 0L

    private fun place() {
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0 || (w.toLong() shl 32 or h.toLong()) == lastSize) return
        lastSize = w.toLong() shl 32 or h.toLong()
        val areas = GameLayout.compute(w, h, 0, 0, 0, 0)
        game.layoutParams = FrameLayout.LayoutParams(areas.game.width(), areas.game.height()).apply {
            leftMargin = areas.game.left
            topMargin = areas.game.top
        }
        controls.layoutParams = FrameLayout.LayoutParams(areas.controls.width(), areas.controls.height()).apply {
            leftMargin = areas.controls.left
            topMargin = areas.controls.top
        }
        root.requestLayout()
    }

    private fun save() {
        services.settings.update { it.withTouchLayout(working.normalized()) }
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        const val EXTRA_ORIENTATION = "orientation"
    }

}
