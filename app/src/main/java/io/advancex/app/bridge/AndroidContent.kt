// SPDX-License-Identifier: MPL-2.0
package io.advancex.app.bridge

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.advancex.engine.content.ContentOrigin
import io.advancex.engine.content.ContentPaths
import io.advancex.engine.content.ContentRoot
import io.advancex.engine.replacements.ArgbImage
import io.advancex.engine.replacements.ImageCodec
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Advance Engine content shipped inside the APK (`assets/<prefix>/...`). */
class AssetContentRoot(private val assets: AssetManager, private val prefix: String, override val origin: ContentOrigin) :
    ContentRoot {
    override fun list(dir: String): List<String> {
        val path = ContentPaths.normalize(dir) ?: return emptyList()
        return runCatching { assets.list(join(path))?.toList()?.sorted() ?: emptyList() }.getOrDefault(emptyList())
    }

    override fun read(path: String): ByteArray? {
        val p = ContentPaths.normalize(path) ?: return null
        return try {
            assets.open(join(p)).use { it.readBytes() }
        } catch (e: IOException) {
            null
        }
    }

    private fun join(path: String) = if (path.isEmpty()) prefix else "$prefix/$path"
}

/** PNG/JPEG decoding and PNG encoding through android.graphics. */
object AndroidImageCodec : ImageCodec {
    override fun decode(bytes: ByteArray): ArgbImage? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            inPremultiplied = false
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        if (bitmap.width > 1024 || bitmap.height > 1024) {
            bitmap.recycle()
            return null
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val image = ArgbImage(bitmap.width, bitmap.height, pixels)
        bitmap.recycle()
        return image
    }

    override fun encodePng(image: ArgbImage): ByteArray {
        val bitmap = Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}
