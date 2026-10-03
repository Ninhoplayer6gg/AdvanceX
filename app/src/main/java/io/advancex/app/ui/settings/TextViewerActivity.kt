// SPDX-License-Identifier: MPL-2.0
package io.advancex.app.ui.settings

import android.os.Bundle
import android.graphics.Typeface
import io.advancex.app.ui.common.BaseActivity
import io.advancex.app.ui.common.Nav
import io.advancex.app.ui.common.TextStyle
import io.advancex.app.ui.common.text

/** Shows a text document bundled in the APK assets (e.g. third-party licenses). */
class TextViewerActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val asset = intent.getStringExtra(Nav.EXTRA_TEXT_ASSET) ?: return finish()
        val title = intent.getStringExtra(Nav.EXTRA_TITLE)
        val body = runCatching { assets.open(asset).use { it.readBytes().toString(Charsets.UTF_8) } }
            .getOrElse { "This document is not included in this build." }
        val content = contentColumn {
            addView(text(body, TextStyle.CAPTION, io.advancex.app.ui.common.Palette.text(context)).apply {
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextIsSelectable(true)
            })
        }
        setScreen(title, content)
    }
}
