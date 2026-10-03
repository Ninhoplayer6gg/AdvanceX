// SPDX-License-Identifier: MPL-2.0
package io.advancex.engine.replacements

import io.advancex.engine.graphics.FeatureStatus

/**
 * Upscaling step of the asset pipeline:
 *
 *   original asset -> upscaler -> cache -> replacement
 *
 * Classic pixel-art algorithms ship today. An AI upscaler can be added later
 * behind this same interface (e.g. an on-device model); it will always be
 * optional, and emulation never depends on it.
 */
interface AssetUpscaler {
    val id: String
    val name: String
    val factor: Int
    val status: FeatureStatus
    /** Returns null if this upscaler is not available on the device. */
    fun upscale(image: ArgbImage): ArgbImage?
}

/** Integer nearest-neighbour enlargement (identity look, more pixels). */
class NearestUpscaler(override val factor: Int) : AssetUpscaler {
    init {
        require(factor in 2..8)
    }

    override val id = "nearest${factor}x"
    override val name = "Nearest ${factor}×"
    override val status = FeatureStatus.STABLE

    override fun upscale(image: ArgbImage): ArgbImage {
        val w = image.width * factor
        val h = image.height * factor
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val sy = y / factor
            for (x in 0 until w) out[y * w + x] = image.pixels[sy * image.width + x / factor]
        }
        return ArgbImage(w, h, out)
    }
}

/**
 * Scale2x / EPX edge-directed 2× upscaler: smooths diagonal staircases in
 * pixel art without blurring or inventing colours. Implemented from the
 * algorithm's published rules.
 */
class Scale2xUpscaler : AssetUpscaler {
    override val id = "scale2x"
    override val name = "Scale2x (pixel art)"
    override val factor = 2
    override val status = FeatureStatus.EXPERIMENTAL

    override fun upscale(image: ArgbImage): ArgbImage {
        val w = image.width
        val h = image.height
        val out = IntArray(w * h * 4)
        val ow = w * 2
        fun px(x: Int, y: Int) = image.pixels[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = px(x, y)
                val a = px(x, y - 1) // up
                val b = px(x + 1, y) // right
                val c = px(x - 1, y) // left
                val d = px(x, y + 1) // down
                var e0 = p
                var e1 = p
                var e2 = p
                var e3 = p
                if (a != d && c != b) {
                    if (c == a) e0 = a
                    if (a == b) e1 = b
                    if (c == d) e2 = c
                    if (d == b) e3 = d
                }
                val o = (y * 2) * ow + x * 2
                out[o] = e0
                out[o + 1] = e1
                out[o + ow] = e2
                out[o + ow + 1] = e3
            }
        }
        return ArgbImage(ow, h * 2, out)
    }
}

/** Placeholder entry so the UI can show AI upscaling as planned, not missing. */
object AiUpscalerSlot : AssetUpscaler {
    override val id = "ai"
    override val name = "AI upscaling"
    override val factor = 4
    override val status = FeatureStatus.COMING_LATER
    override fun upscale(image: ArgbImage): ArgbImage? = null
}

object Upscalers {
    val all: List<AssetUpscaler> = listOf(Scale2xUpscaler(), NearestUpscaler(2), NearestUpscaler(4), AiUpscalerSlot)
    fun byId(id: String): AssetUpscaler? = all.firstOrNull { it.id == id }
}
