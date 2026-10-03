// SPDX-License-Identifier: MPL-2.0
package io.advancex.engine.replacements

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Disk cache for processed assets (decoded and/or upscaled replacement
 * images), so PNG decoding and upscaling happen once rather than on every
 * game launch.
 *
 * Keys are derived from the source bytes plus the processing step, so a
 * changed source file or a different upscaler never returns stale results.
 * Entries are deflate-compressed ARGB; the least recently used entries are
 * evicted once [maxBytes] is exceeded.
 */
class AssetCache(private val dir: File, private val maxBytes: Long = 128L * 1024 * 1024) {
    init {
        dir.mkdirs()
    }

    fun keyFor(source: ByteArray, process: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(process.toByteArray())
        md.update(0)
        md.update(source)
        return md.digest().joinToString("") { "%02x".format(it) }.take(40)
    }

    fun get(key: String): ArgbImage? {
        val f = file(key)
        if (!f.isFile) return null
        return runCatching {
            DataInputStream(InflaterInputStream(f.inputStream().buffered())).use { input ->
                if (input.readInt() != MAGIC) return null
                val w = input.readInt()
                val h = input.readInt()
                if (w <= 0 || h <= 0 || w > 4096 || h > 4096) return null
                val pixels = IntArray(w * h) { input.readInt() }
                f.setLastModified(System.currentTimeMillis())
                ArgbImage(w, h, pixels)
            }
        }.getOrNull()
    }

    fun put(key: String, image: ArgbImage) {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(DeflaterOutputStream(bytes)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(image.width)
            out.writeInt(image.height)
            for (i in 0 until image.width * image.height) out.writeInt(image.pixels[i])
        }
        val target = file(key)
        val tmp = File(target.path + ".tmp")
        tmp.writeBytes(bytes.toByteArray())
        tmp.renameTo(target)
        trim()
    }

    /** Returns the cached result for (source, process), computing and storing it if absent. */
    fun getOrPut(source: ByteArray, process: String, compute: () -> ArgbImage?): ArgbImage? {
        val key = keyFor(source, process)
        get(key)?.let { return it }
        val result = compute() ?: return null
        put(key, result)
        return result
    }

    fun sizeBytes(): Long = dir.listFiles()?.filter { it.name.endsWith(EXT) }?.sumOf { it.length() } ?: 0

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun trim() {
        val files = dir.listFiles()?.filter { it.name.endsWith(EXT) }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }

    private fun file(key: String) = File(dir, key + EXT)

    companion object {
        private const val MAGIC = 0x41584943 // "AXIC"
        private const val EXT = ".axic"
    }
}
