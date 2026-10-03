// SPDX-License-Identifier: MPL-2.0
package io.advancex.engine.replacements

import io.advancex.engine.AdvanceJson
import io.advancex.engine.content.ContentPaths
import io.advancex.engine.content.ContentRoot
import kotlinx.serialization.Serializable

/**
 * Kinds of assets the replacement system is designed for. Only [SPRITE] is
 * implemented (experimental); the others are reserved so packs can already
 * be authored against a stable format.
 */
enum class AssetKind(val id: String, val supported: Boolean) {
    SPRITE("sprite", true),
    TILE("tile", false),
    BACKGROUND("background", false),
    INTERFACE("interface", false),
    FONT("font", false),
    PORTRAIT("portrait", false),
    EFFECT("effect", false);

    companion object {
        fun of(id: String): AssetKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * `replacements/<name>.json`:
 * ```json
 * {
 *   "schemaVersion": 1,
 *   "kind": "sprite",
 *   "entries": [
 *     { "key": "0123456789abcdef", "file": "crystal_hd.png", "note": "player crystal" }
 *   ]
 * }
 * ```
 * `key` is the 64-bit fingerprint reported by the sprite detector (tile data
 * + palette), as 16 hex digits. `file` is relative to the JSON file.
 */
@Serializable
data class ReplacementPackFile(
    val schemaVersion: Int = 1,
    val kind: String = "sprite",
    val entries: List<ReplacementEntryDefinition> = emptyList(),
)

@Serializable
data class ReplacementEntryDefinition(val key: String, val file: String, val note: String? = null)

/** A resolved replacement: fingerprint -> image inside a content root. */
data class ReplacementEntry(val key: Long, val kind: AssetKind, val root: ContentRoot, val path: String, val note: String?) {
    val keyHex: String get() = "%016x".format(key)
}

data class ReplacementLoadResult(val entries: List<ReplacementEntry>, val problems: List<String>)

object ReplacementPacks {
    fun load(root: ContentRoot, packPath: String): ReplacementLoadResult {
        val problems = mutableListOf<String>()
        val text = root.readText(packPath) ?: return ReplacementLoadResult(emptyList(), listOf("$packPath not found"))
        val file = runCatching { AdvanceJson.decodeFromString(ReplacementPackFile.serializer(), text) }
            .getOrElse { return ReplacementLoadResult(emptyList(), listOf("$packPath: invalid JSON (${it.message})")) }
        val kind = AssetKind.of(file.kind)
        if (kind == null) return ReplacementLoadResult(emptyList(), listOf("$packPath: unknown kind '${file.kind}'"))
        if (!kind.supported) {
            return ReplacementLoadResult(emptyList(), listOf("$packPath: '${kind.id}' replacement is not supported yet (coming later)"))
        }
        val entries = file.entries.mapNotNull { e ->
            val key = parseKey(e.key)
            val path = ContentPaths.sibling(packPath, e.file)
            when {
                key == null -> { problems += "${e.key}: key must be 16 hex digits"; null }
                path == null -> { problems += "${e.file}: invalid path"; null }
                root.read(path) == null && root.file(path) == null -> { problems += "$path: file missing"; null }
                else -> ReplacementEntry(key, kind, root, path, e.note)
            }
        }
        return ReplacementLoadResult(entries, problems)
    }

    fun parseKey(text: String): Long? {
        val t = text.trim().lowercase()
        if (t.length != 16 || !t.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return java.lang.Long.parseUnsignedLong(t, 16)
    }
}
