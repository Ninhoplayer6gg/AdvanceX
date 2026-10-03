// SPDX-License-Identifier: MPL-2.0
package io.advancex.app.ui.emulation

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import io.advancex.app.ui.common.Palette
import io.advancex.app.ui.common.dpf
import io.advancex.core.input.ControlId
import io.advancex.core.input.ControlPlacement
import io.advancex.core.input.DirectionalInput
import io.advancex.core.input.GbaButton
import io.advancex.core.input.TouchLayout
import kotlin.math.hypot

/**
 * On-screen GBA controls.
 *
 * - multi-touch with any number of fingers
 * - 8-way D-pad with equal sectors and a small dead zone
 * - fingers can slide between face/shoulder buttons without lifting
 * - generous hit areas (130% of the drawn size)
 * - edit mode: drag to move, select to resize or hide
 *
 * No allocations happen in onTouchEvent/onDraw during play.
 */
class TouchControlsView(context: Context) : View(context) {
    interface Listener {
        /** Full GBA key mask produced by touch (DPAD + buttons). */
        fun onButtons(mask: Int)
        /** FAST_FORWARD / REWIND / MENU presses and releases. */
        fun onHotkey(id: ControlId, pressed: Boolean)
    }

    var listener: Listener? = null
    var onLayoutEdited: ((TouchLayout) -> Unit)? = null

    var layoutModel: TouchLayout = TouchLayout.DEFAULT_LANDSCAPE
        set(value) {
            field = value
            place()
            invalidate()
        }

    var editMode = false
        set(value) {
            field = value
            releaseAll()
            selected = null
            invalidate()
        }

    var selected: ControlId? = null
        private set

    private class Placed(var id: ControlId, var cx: Float, var cy: Float, var size: Float, var visible: Boolean)

    private val placed = ArrayList<Placed>()
    private val pointerTarget = arrayOfNulls<ControlId>(MAX_POINTERS)
    private val pointerDpad = IntArray(MAX_POINTERS)
    private var lastMask = 0
    private val hotkeysDown = BooleanArray(ControlId.entries.size)
    private val hotkeysScratch = BooleanArray(ControlId.entries.size)
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var dragging = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(2)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val selection = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(2)
        pathEffect = DashPathEffect(floatArrayOf(context.dpf(8), context.dpf(6)), 0f)
        color = Palette.cyan(context)
    }
    private val path = Path()
    private val rect = RectF()
    private val violet = Palette.violet(context)
    private val cyan = Palette.cyan(context)
    private val body = 0xFF1E2235.toInt()

    init {
        isHapticFeedbackEnabled = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = place()

    private fun place() {
        placed.clear()
        val scale = layoutModel.scale
        for (c in layoutModel.controls) {
            placed += Placed(c.id, c.x * width, c.y * height, context.dpf(c.sizeDp) * scale, c.visible)
        }
    }

    private fun find(id: ControlId): Placed? {
        for (p in placed) if (p.id == id) return p
        return null
    }

    // --- Touch handling -------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode) return onEditTouch(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                bind(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    if (id >= MAX_POINTERS) continue
                    val target = pointerTarget[id]
                    if (target == ControlId.DPAD) {
                        updateDpad(id, event.getX(i), event.getY(i))
                    } else if (target == null || isSlidable(target)) {
                        // Slide between buttons (e.g. A -> B) without lifting.
                        val under = hit(event.getX(i), event.getY(i), slidableOnly = true)
                        if (under != target) pointerTarget[id] = under
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                if (id < MAX_POINTERS) {
                    pointerTarget[id] = null
                    pointerDpad[id] = 0
                }
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        publish()
        return true
    }

    private fun bind(pointerId: Int, x: Float, y: Float) {
        if (pointerId >= MAX_POINTERS) return
        val target = hit(x, y, slidableOnly = false)
        pointerTarget[pointerId] = target
        pointerDpad[pointerId] = 0
        if (target == ControlId.DPAD) updateDpad(pointerId, x, y)
    }

    private fun updateDpad(pointerId: Int, x: Float, y: Float) {
        val d = find(ControlId.DPAD) ?: return
        pointerDpad[pointerId] = DirectionalInput.toMask(x - d.cx, y - d.cy, d.size * 0.12f)
    }

    private fun isSlidable(id: ControlId) = id == ControlId.A || id == ControlId.B || id == ControlId.L || id == ControlId.R

    private fun hit(x: Float, y: Float, slidableOnly: Boolean): ControlId? {
        var best: ControlId? = null
        var bestDistance = Float.MAX_VALUE
        for (p in placed) {
            if (!p.visible) continue
            if (slidableOnly && !isSlidable(p.id)) continue
            val dx = (x - p.cx) / (halfW(p) * 1.3f)
            val dy = (y - p.cy) / (halfH(p) * 1.3f)
            val dist = dx * dx + dy * dy
            if (dist <= 1f && dist < bestDistance) {
                best = p.id
                bestDistance = dist
            }
        }
        return best
    }

    private fun halfW(p: Placed): Float = p.size / 2

    private fun halfH(p: Placed): Float = when (p.id) {
        ControlId.L, ControlId.R -> p.size * 0.26f
        ControlId.START, ControlId.SELECT -> p.size * 0.24f
        else -> p.size / 2
    }

    private fun releaseAll() {
        pointerTarget.fill(null)
        pointerDpad.fill(0)
        publish()
    }

    private fun publish() {
        var mask = 0
        hotkeysScratch.fill(false)
        for (i in 0 until MAX_POINTERS) {
            when (val t = pointerTarget[i]) {
                null -> Unit
                ControlId.DPAD -> mask = mask or pointerDpad[i]
                ControlId.A -> mask = mask or GbaButton.A.mask
                ControlId.B -> mask = mask or GbaButton.B.mask
                ControlId.L -> mask = mask or GbaButton.L.mask
                ControlId.R -> mask = mask or GbaButton.R.mask
                ControlId.START -> mask = mask or GbaButton.START.mask
                ControlId.SELECT -> mask = mask or GbaButton.SELECT.mask
                else -> hotkeysScratch[t.ordinal] = true
            }
        }
        if (mask != lastMask) {
            if (layoutModel.haptics && (mask and lastMask.inv()) != 0) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            lastMask = mask
            listener?.onButtons(mask)
            invalidate()
        }
        for (i in hotkeysScratch.indices) {
            if (hotkeysScratch[i] != hotkeysDown[i]) {
                hotkeysDown[i] = hotkeysScratch[i]
                if (hotkeysScratch[i] && layoutModel.haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                listener?.onHotkey(ControlId.entries[i], hotkeysScratch[i])
                invalidate()
            }
        }
    }

    // --- Edit mode --------------------------------------------------------------

    private fun onEditTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val target = hitAny(event.x, event.y)
                selected = target
                dragging = target != null
                if (target != null) {
                    val p = find(target)!!
                    dragOffsetX = event.x - p.cx
                    dragOffsetY = event.y - p.cy
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val p = selected?.let { find(it) } ?: return true
                p.cx = (event.x - dragOffsetX).coerceIn(0f, width.toFloat())
                p.cy = (event.y - dragOffsetY).coerceIn(0f, height.toFloat())
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) commitEdit()
                dragging = false
            }
        }
        return true
    }

    private fun hitAny(x: Float, y: Float): ControlId? {
        var best: ControlId? = null
        var bestDistance = Float.MAX_VALUE
        for (p in placed) {
            val d = hypot(x - p.cx, y - p.cy)
            if (d < p.size * 0.7f && d < bestDistance) {
                best = p.id
                bestDistance = d
            }
        }
        return best
    }

    /** Changes the selected control's size by [deltaDp]. */
    fun resizeSelected(deltaDp: Float) {
        val id = selected ?: return
        val current = layoutModel.placement(id) ?: return
        layoutModel = layoutModel.with(current.copy(sizeDp = current.sizeDp + deltaDp))
        selected = id
        onLayoutEdited?.invoke(layoutModel)
    }

    fun toggleSelectedVisibility() {
        val id = selected ?: return
        val current = layoutModel.placement(id) ?: return
        layoutModel = layoutModel.with(current.copy(visible = !current.visible))
        selected = id
        onLayoutEdited?.invoke(layoutModel)
    }

    private fun commitEdit() {
        val id = selected ?: return
        val p = find(id) ?: return
        val current = layoutModel.placement(id) ?: return
        layoutModel = layoutModel.with(
            ControlPlacement(id, if (width > 0) p.cx / width else current.x, if (height > 0) p.cy / height else current.y,
                current.sizeDp, current.visible),
        )
        selected = id
        onLayoutEdited?.invoke(layoutModel)
    }

    // --- Drawing ----------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val baseAlpha = (layoutModel.opacity * 255).toInt().coerceIn(25, 255)
        for (p in placed) {
            if (!p.visible && !editMode) continue
            val alpha = if (!p.visible) 70 else baseAlpha
            when (p.id) {
                ControlId.DPAD -> drawDpad(canvas, p, alpha)
                ControlId.A -> drawRound(canvas, p, "A", violet, isPressed(GbaButton.A), alpha)
                ControlId.B -> drawRound(canvas, p, "B", cyan, isPressed(GbaButton.B), alpha)
                ControlId.L -> drawShoulder(canvas, p, "L", isPressed(GbaButton.L), alpha)
                ControlId.R -> drawShoulder(canvas, p, "R", isPressed(GbaButton.R), alpha)
                ControlId.START -> drawPill(canvas, p, "START", isPressed(GbaButton.START), alpha)
                ControlId.SELECT -> drawPill(canvas, p, "SELECT", isPressed(GbaButton.SELECT), alpha)
                ControlId.FAST_FORWARD, ControlId.REWIND, ControlId.MENU ->
                    drawHotkey(canvas, p, hotkeysDown[p.id.ordinal], alpha)
            }
            if (editMode && p.id == selected) {
                val hw = halfW(p)
                val hh = halfH(p)
                rect.set(p.cx - hw - 8, p.cy - hh - 8, p.cx + hw + 8, p.cy + hh + 8)
                canvas.drawRoundRect(rect, 16f, 16f, selection)
            }
        }
    }

    private fun isPressed(b: GbaButton) = lastMask and b.mask != 0

    private fun drawRound(canvas: Canvas, p: Placed, text: String, accent: Int, pressed: Boolean, alpha: Int) {
        val r = p.size / 2
        fill.color = if (pressed) accent else body
        fill.alpha = if (pressed) 255.coerceAtMost(alpha + 60) else alpha
        canvas.drawCircle(p.cx, p.cy, r, fill)
        stroke.color = accent
        stroke.alpha = alpha
        canvas.drawCircle(p.cx, p.cy, r - stroke.strokeWidth, stroke)
        label.color = 0xFFFFFFFF.toInt()
        label.alpha = alpha.coerceAtLeast(160)
        label.textSize = r * 0.8f
        canvas.drawText(text, p.cx, p.cy - (label.descent() + label.ascent()) / 2, label)
    }

    private fun drawShoulder(canvas: Canvas, p: Placed, text: String, pressed: Boolean, alpha: Int) {
        val hw = halfW(p)
        val hh = halfH(p)
        rect.set(p.cx - hw, p.cy - hh, p.cx + hw, p.cy + hh)
        fill.color = if (pressed) violet else body
        fill.alpha = alpha
        canvas.drawRoundRect(rect, hh, hh, fill)
        stroke.color = 0xFF3A4060.toInt()
        stroke.alpha = alpha
        canvas.drawRoundRect(rect, hh, hh, stroke)
        label.color = 0xFFFFFFFF.toInt()
        label.alpha = alpha.coerceAtLeast(160)
        label.textSize = hh * 1.0f
        canvas.drawText(text, p.cx, p.cy - (label.descent() + label.ascent()) / 2, label)
    }

    private fun drawPill(canvas: Canvas, p: Placed, text: String, pressed: Boolean, alpha: Int) {
        val hw = halfW(p)
        val hh = halfH(p)
        rect.set(p.cx - hw, p.cy - hh, p.cx + hw, p.cy + hh)
        fill.color = if (pressed) violet else body
        fill.alpha = alpha
        canvas.drawRoundRect(rect, hh, hh, fill)
        label.color = 0xFFD0D4F0.toInt()
        label.alpha = alpha.coerceAtLeast(150)
        label.textSize = hh * 0.85f
        canvas.drawText(text, p.cx, p.cy - (label.descent() + label.ascent()) / 2, label)
    }

    private fun drawDpad(canvas: Canvas, p: Placed, alpha: Int) {
        val s = p.size
        val arm = s / 3
        val half = s / 2
        fill.color = body
        fill.alpha = alpha
        rect.set(p.cx - arm / 2, p.cy - half, p.cx + arm / 2, p.cy + half)
        canvas.drawRoundRect(rect, arm * 0.25f, arm * 0.25f, fill)
        rect.set(p.cx - half, p.cy - arm / 2, p.cx + half, p.cy + arm / 2)
        canvas.drawRoundRect(rect, arm * 0.25f, arm * 0.25f, fill)

        // Highlight pressed directions.
        fill.color = violet
        fill.alpha = 255.coerceAtMost(alpha + 60)
        if (isPressed(GbaButton.UP)) canvas.drawRect(p.cx - arm / 2, p.cy - half, p.cx + arm / 2, p.cy - arm / 2, fill)
        if (isPressed(GbaButton.DOWN)) canvas.drawRect(p.cx - arm / 2, p.cy + arm / 2, p.cx + arm / 2, p.cy + half, fill)
        if (isPressed(GbaButton.LEFT)) canvas.drawRect(p.cx - half, p.cy - arm / 2, p.cx - arm / 2, p.cy + arm / 2, fill)
        if (isPressed(GbaButton.RIGHT)) canvas.drawRect(p.cx + arm / 2, p.cy - arm / 2, p.cx + half, p.cy + arm / 2, fill)

        // Arrow hints.
        fill.color = 0xFFD0D4F0.toInt()
        fill.alpha = alpha.coerceAtLeast(120)
        val t = arm * 0.22f
        val o = half - arm * 0.45f
        triangle(canvas, p.cx, p.cy - o, 0f, -1f, t)
        triangle(canvas, p.cx, p.cy + o, 0f, 1f, t)
        triangle(canvas, p.cx - o, p.cy, -1f, 0f, t)
        triangle(canvas, p.cx + o, p.cy, 1f, 0f, t)
    }

    private fun triangle(canvas: Canvas, x: Float, y: Float, dx: Float, dy: Float, size: Float) {
        path.reset()
        path.moveTo(x + dx * size, y + dy * size)
        path.lineTo(x - dy * size - dx * size * 0.4f, y + dx * size - dy * size * 0.4f)
        path.lineTo(x + dy * size - dx * size * 0.4f, y - dx * size - dy * size * 0.4f)
        path.close()
        canvas.drawPath(path, fill)
    }

    private fun drawHotkey(canvas: Canvas, p: Placed, active: Boolean, alpha: Int) {
        val r = p.size / 2
        fill.color = if (active) cyan else body
        fill.alpha = alpha
        canvas.drawCircle(p.cx, p.cy, r, fill)
        fill.color = 0xFFFFFFFF.toInt()
        fill.alpha = alpha.coerceAtLeast(160)
        val s = r * 0.42f
        when (p.id) {
            ControlId.FAST_FORWARD -> {
                triangle(canvas, p.cx - s * 0.35f, p.cy, 1f, 0f, s * 0.75f)
                triangle(canvas, p.cx + s * 0.55f, p.cy, 1f, 0f, s * 0.75f)
            }
            ControlId.REWIND -> {
                triangle(canvas, p.cx + s * 0.35f, p.cy, -1f, 0f, s * 0.75f)
                triangle(canvas, p.cx - s * 0.55f, p.cy, -1f, 0f, s * 0.75f)
            }
            else -> {
                val w = s * 1.2f
                val h = s * 0.18f
                for (k in -1..1) canvas.drawRect(p.cx - w / 2, p.cy + k * s * 0.55f - h / 2, p.cx + w / 2, p.cy + k * s * 0.55f + h / 2, fill)
            }
        }
    }

    companion object {
        private const val MAX_POINTERS = 16
    }
}
