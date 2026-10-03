// SPDX-License-Identifier: MPL-2.0
package io.advancex.app.ui.common

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import io.advancex.app.AppServices
import io.advancex.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/**
 * Base for all AdvanceX screens: a coroutine scope bound to the activity,
 * access to app services, and a standard scaffold (top bar + scrolling
 * content) that respects system bar insets (edge-to-edge on Android 15+).
 */
abstract class BaseActivity : Activity() {
    val scope: CoroutineScope = MainScope()
    val services: AppServices get() = AppServices.get(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = Palette.bg(this)
            window.navigationBarColor = Palette.bg(this)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Sets a standard screen: top bar with [title] (and a back button unless
     * [showBack] is false), optional [actions], and scrollable [content].
     */
    fun setScreen(title: CharSequence?, content: View, showBack: Boolean = true, actions: List<View> = emptyList(),
                  scroll: Boolean = true, footer: View? = null) {
        val root = vertical {
            setBackgroundColor(Palette.bg(context))
        }
        val bar = horizontal {
            minimumHeight = dp(64)
            setPadding(dp(4), dp(4), dp(8), dp(4))
            if (showBack) addView(iconButton(R.drawable.ic_back, getString(R.string.action_back)) { onBackPressed() })
            else addView(spacerWidth(dp(12)))
            if (title != null) add(text(title, TextStyle.TITLE).apply { setPadding(dp(4), 0, 0, 0) }, width = 0, weight = 1f)
            else add(View(context), width = 0, weight = 1f)
            actions.forEach { addView(it) }
        }
        root.addView(bar)
        val body: View = if (scroll) {
            ScrollView(this).apply {
                isFillViewport = true
                clipToPadding = false
                addView(content)
            }
        } else content
        root.add(body, height = 0, weight = 1f)
        if (footer != null) root.addView(footer)
        applySystemBarInsets(root)
        setContentView(root)
    }

    private fun spacerWidth(px: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(px, 1) }

    /** Pads [view] so content never sits under the status/navigation bars or a cutout. */
    fun applySystemBarInsets(view: View) {
        view.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        if (Build.VERSION.SDK_INT < 35) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            window.statusBarColor = 0
            window.navigationBarColor = 0x66000000
        }
    }

    fun toast(message: CharSequence) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    fun showMessage(title: CharSequence, message: CharSequence, onOk: (() -> Unit)? = null) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> onOk?.invoke() }
            .setOnCancelListener { onOk?.invoke() }
            .show()
    }

    fun confirm(title: CharSequence, message: CharSequence, confirmLabel: CharSequence, onConfirm: () -> Unit) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(confirmLabel) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Standard vertical content column with comfortable side padding. */
    fun contentColumn(block: LinearLayout.() -> Unit): LinearLayout = vertical {
        setPadding(dp(16), dp(4), dp(16), dp(32))
        block()
    }

    /** Centered empty-state block. */
    fun emptyState(iconRes: Int, title: CharSequence, message: CharSequence, action: View?): View = vertical {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(48), dp(24), dp(48))
        add(icon(iconRes, Palette.violet(context), 56), width = dp(56), height = dp(56), bottomMarginDp = 16)
        addView(text(title, TextStyle.TITLE).apply { gravity = Gravity.CENTER })
        add(text(message, TextStyle.BODY_SECONDARY).apply { gravity = Gravity.CENTER }, topMarginDp = 8, bottomMarginDp = 24)
        if (action != null) add(action, width = ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun overlayFrame(content: View): FrameLayout = FrameLayout(this).apply { addView(content) }
}
