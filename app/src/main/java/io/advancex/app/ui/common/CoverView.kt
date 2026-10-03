// SPDX-License-Identifier: MPL-2.0
package io.advancex.app.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import io.advancex.app.data.GameEntry
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Game cover. Shows the user's custom cover or the last screenshot when
 * available (pixel-art friendly nearest-neighbour scaling); otherwise draws
 * a generated cover from the game's hash and title. AdvanceX never ships or
 * downloads copyrighted box art.
 */
class CoverView(context: Context) : View(context) {
    private var entry: GameEntry? = null
    private var bitmap: Bitmap? = null
    private val radius = context.dpf(16)
    private val clip = Path()
    private val rect = RectF()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val bitmapPaint = Paint().apply { isFilterBitmap = false }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.1f
    }
    private val motif = Path()
    var aspect = 2f / 3f // height = width * aspect
    var showTitle = true

    fun bind(game: GameEntry, coverFile: File?) {
        entry = game
        bitmap = coverFile?.let { loadCached(it) }
        contentDescription = game.title
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec)
        else (w * aspect).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        val game = entry ?: return
        canvas.save()
        canvas.clipPath(clip)
        val bmp = bitmap
        if (bmp != null) {
            drawCropped(canvas, bmp)
            if (showTitle) drawTitleShade(canvas)
        } else {
            drawGenerated(canvas, game)
        }
        if (showTitle) drawTitle(canvas, game)
        canvas.restore()
    }

    private fun drawCropped(canvas: Canvas, bmp: Bitmap) {
        val viewAspect = width.toFloat() / height
        val bmpAspect = bmp.width.toFloat() / bmp.height
        if (bmpAspect > viewAspect) {
            val w = (bmp.height * viewAspect).toInt()
            val x = (bmp.width - w) / 2
            srcRect.set(x, 0, x + w, bmp.height)
        } else {
            val h = (bmp.width / viewAspect).toInt()
            val y = (bmp.height - h) / 2
            srcRect.set(0, y, bmp.width, y + h)
        }
        dstRect.set(rect)
        canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
    }

    private fun drawGenerated(canvas: Canvas, game: GameEntry) {
        val seed = game.sha1.take(8).toLongOrNull(16) ?: game.id.hashCode().toLong()
        val hue = (seed % 360).toFloat()
        val c1 = Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.55f))
        val c2 = Color.HSVToColor(floatArrayOf((hue + 50) % 360, 0.75f, 0.25f))
        fill.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), c1, c2, Shader.TileMode.CLAMP)
        canvas.drawRect(rect, fill)
        fill.shader = null
        // Crystal motif (the AdvanceX mark) as a large translucent shape.
        val cx = width * 0.72f
        val cy = height * 0.38f
        val s = height * 0.55f
        motif.reset()
        motif.moveTo(cx, cy - s)
        motif.lineTo(cx + s * 0.75f, cy)
        motif.lineTo(cx, cy + s)
        motif.lineTo(cx - s * 0.75f, cy)
        motif.close()
        fill.color = 0x22FFFFFF
        canvas.drawPath(motif, fill)
        fill.color = 0x14FFFFFF
        canvas.drawCircle(width * 0.15f, height * 0.9f, height * 0.45f, fill)

        badgePaint.textSize = context.dpf(10)
        val badge = if (game.gameCode.isNotBlank()) "GBA · ${game.gameCode}" else "GBA"
        val pad = context.dpf(10)
        val bw = badgePaint.measureText(badge) + pad * 1.4f
        fill.color = 0x33000000
        canvas.drawRoundRect(pad, pad, pad + bw, pad + context.dpf(20), context.dpf(10), context.dpf(10), fill)
        canvas.drawText(badge, pad + pad * 0.7f, pad + context.dpf(14), badgePaint)
        drawTitleShade(canvas)
    }

    private fun drawTitleShade(canvas: Canvas) {
        shadePaint.shader = LinearGradient(0f, height * 0.45f, 0f, height.toFloat(), 0x00000000, 0xCC000000.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, height * 0.45f, width.toFloat(), height.toFloat(), shadePaint)
    }

    private fun drawTitle(canvas: Canvas, game: GameEntry) {
        val pad = context.dpf(12)
        titlePaint.textSize = if (width > context.dp(220)) context.dpf(20) else context.dpf(15)
        val availableWidth = (width - pad * 2).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(game.title, 0, game.title.length, titlePaint, availableWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.translate(pad, height - pad - layout.height)
        layout.draw(canvas)
        canvas.restore()
    }

    companion object {
        private val cache = ConcurrentHashMap<String, Pair<Long, Bitmap>>()

        /** Small screenshot bitmaps are cached by path + modification time. */
        private fun loadCached(file: File): Bitmap? {
            if (!file.isFile) return null
            val key = file.absolutePath
            val stamp = file.lastModified()
            cache[key]?.let { if (it.first == stamp) return it.second }
            val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            cache[key] = stamp to bmp
            return bmp
        }

        fun invalidate(file: File) {
            cache.remove(file.absolutePath)
        }
    }
}
