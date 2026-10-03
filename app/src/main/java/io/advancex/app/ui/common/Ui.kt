// SPDX-License-Identifier: MPL-2.0
package io.advancex.app.ui.common

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import io.advancex.app.R
import io.advancex.engine.graphics.FeatureStatus

/*
 * Small toolkit for building the AdvanceX UI in code with one consistent
 * look (dark surfaces, rounded cards, large touch targets). The app uses only
 * the Android framework, so these helpers play the role a component library
 * would otherwise play.
 */

fun Context.dp(value: Number): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

fun Context.dpf(value: Number): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics)

fun Context.colorOf(id: Int): Int = getColor(id)

object Palette {
    fun bg(c: Context) = c.colorOf(R.color.bg)
    fun surface(c: Context) = c.colorOf(R.color.surface)
    fun surfaceHigh(c: Context) = c.colorOf(R.color.surface_high)
    fun outline(c: Context) = c.colorOf(R.color.outline)
    fun text(c: Context) = c.colorOf(R.color.text_primary)
    fun textSecondary(c: Context) = c.colorOf(R.color.text_secondary)
    fun textTertiary(c: Context) = c.colorOf(R.color.text_tertiary)
    fun violet(c: Context) = c.colorOf(R.color.brand_violet)
    fun cyan(c: Context) = c.colorOf(R.color.brand_cyan)
    fun pink(c: Context) = c.colorOf(R.color.brand_pink)
    fun success(c: Context) = c.colorOf(R.color.success)
    fun warning(c: Context) = c.colorOf(R.color.warning)
    fun danger(c: Context) = c.colorOf(R.color.danger)
}

enum class TextStyle(val sp: Float, val bold: Boolean, val secondary: Boolean = false, val caps: Boolean = false) {
    DISPLAY(32f, true),
    TITLE(22f, true),
    HEADLINE(17f, true),
    BODY(15f, false),
    BODY_SECONDARY(15f, false, secondary = true),
    CAPTION(13f, false, secondary = true),
    LABEL(12f, true, secondary = true, caps = true),
}

fun Context.text(value: CharSequence, style: TextStyle = TextStyle.BODY, color: Int? = null): TextView =
    TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, style.sp)
        setTextColor(color ?: if (style.secondary) Palette.textSecondary(context) else Palette.text(context))
        typeface = if (style.bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
        if (style.caps) {
            isAllCaps = true
            letterSpacing = 0.08f
        }
        if (style == TextStyle.DISPLAY) letterSpacing = 0.14f
        setLineSpacing(0f, 1.1f)
    }

fun roundedBackground(color: Int, radius: Float, strokeColor: Int? = null, strokeWidth: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeColor != null) setStroke(strokeWidth, strokeColor)
    }

fun gradientBackground(colors: IntArray, radius: Float, orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.TL_BR) =
    GradientDrawable(orientation, colors).apply { cornerRadius = radius }

/** Wraps [content] in a ripple so a view gives touch feedback. */
fun Context.ripple(content: Drawable?, radius: Float): Drawable {
    val mask = roundedBackground(Color.WHITE, radius)
    return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask)
}

fun View.onClick(action: () -> Unit) = setOnClickListener { action() }

fun Context.vertical(paddingDp: Int = 0, block: LinearLayout.() -> Unit = {}): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(paddingDp)
        setPadding(p, p, p, p)
        block()
    }

fun Context.horizontal(block: LinearLayout.() -> Unit = {}): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        block()
    }

fun lp(width: Int = ViewGroup.LayoutParams.MATCH_PARENT, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
    LinearLayout.LayoutParams(width, height)

fun LinearLayout.add(view: View, width: Int = ViewGroup.LayoutParams.MATCH_PARENT, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
                     weight: Float = 0f, topMarginDp: Int = 0, bottomMarginDp: Int = 0, startMarginDp: Int = 0, endMarginDp: Int = 0): View {
    val params = LinearLayout.LayoutParams(width, height, weight)
    params.topMargin = context.dp(topMarginDp)
    params.bottomMargin = context.dp(bottomMarginDp)
    params.marginStart = context.dp(startMarginDp)
    params.marginEnd = context.dp(endMarginDp)
    addView(view, params)
    return view
}

fun Context.spacer(heightDp: Int): View = View(this).apply { minimumHeight = dp(heightDp) }

/** A rounded surface card. Clickable cards get a ripple. */
fun Context.card(onClick: (() -> Unit)? = null, color: Int = Palette.surface(this), paddingDp: Int = 16,
                 block: LinearLayout.() -> Unit = {}): LinearLayout =
    vertical(paddingDp) {
        val radius = dpf(18)
        val bg = roundedBackground(color, radius)
        background = if (onClick != null) ripple(bg, radius) else bg
        if (onClick != null) {
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        block()
    }

fun Context.icon(resId: Int, tint: Int = Palette.text(this), sizeDp: Int = 24): ImageView =
    ImageView(this).apply {
        setImageResource(resId)
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

/** 48dp circular icon button. */
fun Context.iconButton(resId: Int, description: CharSequence, tint: Int = Palette.text(this), onClick: () -> Unit): ImageView =
    ImageView(this).apply {
        setImageResource(resId)
        imageTintList = ColorStateList.valueOf(tint)
        contentDescription = description
        val p = dp(12)
        setPadding(p, p, p, p)
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    }

/** Primary call-to-action: violet→cyan gradient pill. */
fun Context.primaryButton(label: CharSequence, iconRes: Int? = null, onClick: () -> Unit): View =
    pillButton(label, iconRes, gradientBackground(intArrayOf(Palette.violet(this), Palette.cyan(this)), dpf(28),
        GradientDrawable.Orientation.LEFT_RIGHT), Color.WHITE, onClick)

fun Context.secondaryButton(label: CharSequence, iconRes: Int? = null, onClick: () -> Unit): View =
    pillButton(label, iconRes, roundedBackground(Palette.surfaceHigh(this), dpf(28), Palette.outline(this), dp(1)),
        Palette.text(this), onClick)

fun Context.dangerButton(label: CharSequence, onClick: () -> Unit): View =
    pillButton(label, null, roundedBackground(0x33F87171, dpf(28)), Palette.danger(this), onClick)

private fun Context.pillButton(label: CharSequence, iconRes: Int?, bg: Drawable, fg: Int, onClick: () -> Unit): View =
    horizontal {
        gravity = Gravity.CENTER
        minimumHeight = dp(52)
        setPadding(dp(20), dp(10), dp(20), dp(10))
        background = ripple(bg, dpf(28))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        if (iconRes != null) {
            add(icon(iconRes, fg, 20), width = dp(20), height = dp(20), endMarginDp = 8)
        }
        addView(text(label, TextStyle.HEADLINE, fg).apply { gravity = Gravity.CENTER })
    }

fun Context.chip(label: CharSequence, color: Int, filled: Boolean = false): TextView =
    text(label, TextStyle.LABEL, if (filled) Color.WHITE else color).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = roundedBackground(if (filled) color else (color and 0x00FFFFFF) or 0x26000000, dpf(12))
    }

/** "Experimental" / "Coming later" badge, or nothing for stable features. */
fun Context.statusBadge(status: FeatureStatus): View? = when (status) {
    FeatureStatus.STABLE -> null
    FeatureStatus.EXPERIMENTAL -> chip(getString(R.string.badge_experimental), Palette.warning(this))
    FeatureStatus.COMING_LATER -> chip(getString(R.string.badge_coming_later), Palette.textTertiary(this))
}

fun Context.sectionHeader(label: CharSequence): TextView =
    text(label, TextStyle.LABEL).apply { setPadding(dp(4), dp(20), dp(4), dp(8)) }

/** A tappable settings row: [icon] title / subtitle [trailing]. */
fun Context.settingRow(title: CharSequence, subtitle: CharSequence? = null, iconRes: Int? = null,
                       trailing: View? = null, badge: View? = null, onClick: (() -> Unit)? = null): LinearLayout =
    horizontal {
        minimumHeight = dp(64)
        setPadding(dp(16), dp(10), dp(12), dp(10))
        val radius = dpf(16)
        val bg = roundedBackground(Palette.surface(context), radius)
        background = if (onClick != null) ripple(bg, radius) else bg
        if (onClick != null) {
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        if (iconRes != null) add(icon(iconRes, Palette.violet(context)), width = dp(24), height = dp(24), endMarginDp = 16)
        val texts = vertical {
            val titleRow = horizontal {
                addView(text(title, TextStyle.HEADLINE).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
                if (badge != null) add(badge, width = ViewGroup.LayoutParams.WRAP_CONTENT, startMarginDp = 8)
            }
            addView(titleRow)
            if (subtitle != null) addView(text(subtitle, TextStyle.CAPTION).apply { setPadding(0, dp(2), 0, 0) })
        }
        add(texts, width = 0, weight = 1f)
        when {
            trailing != null -> add(trailing, width = ViewGroup.LayoutParams.WRAP_CONTENT, startMarginDp = 8)
            onClick != null -> add(icon(R.drawable.ic_chevron_right, Palette.textTertiary(context), 20),
                width = dp(20), height = dp(20), startMarginDp = 8)
        }
    }

fun Context.switchRow(title: CharSequence, subtitle: CharSequence?, checked: Boolean, iconRes: Int? = null,
                      badge: View? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit): LinearLayout {
    val sw = Switch(this).apply {
        isChecked = checked
        isEnabled = enabled
        thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Palette.violet(context), Palette.textSecondary(context)),
        )
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf((Palette.violet(context) and 0x00FFFFFF) or 0x80000000.toInt(), Palette.outline(context)),
        )
        setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
    }
    return settingRow(title, subtitle, iconRes, sw, badge, onClick = if (enabled) ({ sw.toggle() }) else null).apply {
        alpha = if (enabled) 1f else 0.5f
    }
}

/** A row of mutually exclusive choices rendered as chips. */
fun Context.choiceRow(title: CharSequence, subtitle: CharSequence?, options: List<String>, selected: Int,
                      onSelect: (Int) -> Unit): LinearLayout = card(paddingDp = 16) {
    addView(text(title, TextStyle.HEADLINE))
    if (subtitle != null) addView(text(subtitle, TextStyle.CAPTION).apply { setPadding(0, dp(2), 0, dp(4)) })
    val chips = FlowLayout(context, dp(8))
    val views = mutableListOf<TextView>()
    fun render(sel: Int) {
        views.forEachIndexed { i, v ->
            val active = i == sel
            v.setTextColor(if (active) Color.WHITE else Palette.textSecondary(context))
            v.background = ripple(
                if (active) gradientBackground(intArrayOf(Palette.violet(context), (Palette.violet(context) and 0x00FFFFFF) or 0xCC000000.toInt()), dpf(20))
                else roundedBackground(Palette.surfaceHigh(context), dpf(20), Palette.outline(context), dp(1)),
                dpf(20),
            )
        }
    }
    options.forEachIndexed { i, label ->
        val v = text(label, TextStyle.BODY).apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            setOnClickListener {
                render(i)
                onSelect(i)
            }
        }
        views += v
        chips.addView(v)
    }
    render(selected)
    add(chips, topMarginDp = 8)
}

/** Slider with a live value label. [format] renders the value 0..max. */
fun Context.sliderRow(title: CharSequence, max: Int, value: Int, format: (Int) -> String,
                      onChange: (Int) -> Unit): LinearLayout = card(paddingDp = 16) {
    val valueLabel = text(format(value), TextStyle.BODY, Palette.cyan(context))
    addView(horizontal {
        add(text(title, TextStyle.HEADLINE), width = 0, weight = 1f)
        addView(valueLabel)
    })
    val bar = SeekBar(context).apply {
        this.max = max
        progress = value
        progressTintList = ColorStateList.valueOf(Palette.violet(context))
        thumbTintList = ColorStateList.valueOf(Palette.violet(context))
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                valueLabel.text = format(p)
                if (fromUser) onChange(p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) = Unit
            override fun onStopTrackingTouch(s: SeekBar?) = Unit
        })
    }
    add(bar, topMarginDp = 8)
}

/** Simple wrapping layout for chips. */
class FlowLayout(context: Context, private val gap: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            measureChild(c, MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), heightMeasureSpec)
            if (x > 0 && x + c.measuredWidth > maxWidth) {
                x = 0
                y += rowHeight + gap
                rowHeight = 0
            }
            x += c.measuredWidth + gap
            rowHeight = maxOf(rowHeight, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + rowHeight + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxWidth) {
                x = 0
                y += rowHeight + gap
                rowHeight = 0
            }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + gap
            rowHeight = maxOf(rowHeight, c.measuredHeight)
        }
    }
}

fun Context.frame(block: FrameLayout.() -> Unit = {}) = FrameLayout(this).apply(block)
