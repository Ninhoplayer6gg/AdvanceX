// SPDX-License-Identifier: MPL-2.0
package io.advancex.engine.content

import java.io.File

/** Where a piece of Advance Engine content came from. */
sealed class ContentOrigin(val priority: Int) {
    /** Shipped inside the app. */
    data object Bundled : ContentOrigin(0)
    /** Provided by an installed `.advx` mod. */
    data class Mod(val modId: String) : ContentOrigin(1)
    /** Created by the user (e.g. with Advance Studio). Highest priority. */
    data object User : ContentOrigin(2)
}

/**
 * Read-only virtual file tree that profiles, patches and replacement packs
 * are loaded from. Paths use '/' and are relative to the root. Implemented
 * for plain directories here and for APK assets by the Android app.
 */
interface ContentRoot {
    val origin: ContentOrigin
    fun list(dir: String): List<String>
    fun read(path: String): ByteArray?
    /** A real file for [path] if the root is file-backed (lets the app decode images directly). */
    fun file(path: String): File? = null

    fun readText(path: String): String? = read(path)?.toString(Charsets.UTF_8)
}

class DirectoryContentRoot(private val dir: File, override val origin: ContentOrigin) : ContentRoot {
    override fun list(dir: String): List<String> =
        resolve(dir)?.listFiles()?.map { it.name }?.sorted() ?: emptyList()

    override fun read(path: String): ByteArray? = resolve(path)?.takeIf { it.isFile }?.readBytes()

    override fun file(path: String): File? = resolve(path)?.takeIf { it.isFile }

    private fun resolve(path: String): File? {
        val normalized = ContentPaths.normalize(path) ?: return null
        return if (normalized.isEmpty()) dir else File(dir, normalized)
    }
}

object ContentPaths {
    /**
     * Normalizes a relative path ("a/./b/../c" -> "a/c"). Returns null if it
     * is absolute or escapes the root, which blocks path traversal from
     * untrusted mod content.
     */
    fun normalize(path: String): String? {
        if (path.startsWith("/") || path.startsWith("\\") || path.contains(':') || path.contains('\u0000')) return null
        val parts = ArrayDeque<String>()
        for (part in path.replace('\\', '/').split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeLast()
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/")
    }

    /** Resolves [relative] against the directory containing [base]. */
    fun sibling(base: String, relative: String): String? {
        val dir = base.substringBeforeLast('/', "")
        return normalize(if (dir.isEmpty()) relative else "$dir/$relative")
    }
}
