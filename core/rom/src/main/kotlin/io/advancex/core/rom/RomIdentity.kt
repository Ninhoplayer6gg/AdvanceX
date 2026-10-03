// SPDX-License-Identifier: MPL-2.0
package io.advancex.core.rom

/** Content hashes of a ROM image. Hex strings are lowercase. */
data class RomHashes(
    val crc32: String,
    val sha1: String,
    val sha256: String,
    val size: Long,
) {
    /**
     * True if [hash] (CRC32, SHA-1 or SHA-256, detected by length) matches.
     * Comparison is case-insensitive; whitespace is ignored.
     */
    fun matches(hash: String): Boolean {
        val h = hash.filterNot { it.isWhitespace() }.lowercase()
        return when (h.length) {
            8 -> h == crc32
            40 -> h == sha1
            64 -> h == sha256
            else -> false
        }
    }
}

/**
 * Everything AdvanceX knows about a ROM image without running it.
 * Used to pick saves, profiles, patches and mods for a game.
 */
data class RomIdentity(
    val header: GbaHeader,
    val hashes: RomHashes,
) {
    /**
     * Stable identifier used for save folders and per-game settings:
     * `<GAMECODE>_<first 8 hex digits of SHA-1>`, e.g. `AXVE_1f2e3d4c`.
     * Including part of the hash keeps different revisions and ROM hacks
     * that share a game code apart, so they never overwrite each other's saves.
     */
    val gameId: String
        get() {
            val code = header.gameCode.filter { it.isLetterOrDigit() }.uppercase()
            val prefix = if (code.length == 4) code else "HOMEBREW"
            return prefix + "_" + hashes.sha1.take(8)
        }

    /** Human-friendly title (header title, falling back to the game code). */
    val displayTitle: String
        get() = header.title.ifBlank { header.gameCode.ifBlank { "Unknown game" } }
}
