// SPDX-License-Identifier: MPL-2.0
package io.advancex.engine.replacements

/** A simple ARGB_8888 image (same pixel format as android.graphics.Bitmap). */
class ArgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0 && pixels.size >= width * height) { "invalid image ${width}x$height" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    override fun equals(other: Any?): Boolean =
        other is ArgbImage && other.width == width && other.height == height &&
            pixels.copyOf(width * height).contentEquals(other.pixels.copyOf(width * height))

    override fun hashCode(): Int = 31 * (31 * width + height) + pixels.contentHashCode()
}

/** Platform image codec (Android: BitmapFactory / Bitmap.compress). */
interface ImageCodec {
    fun decode(bytes: ByteArray): ArgbImage?
    fun encodePng(image: ArgbImage): ByteArray
}
