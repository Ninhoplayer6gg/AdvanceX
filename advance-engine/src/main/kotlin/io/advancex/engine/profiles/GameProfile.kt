// SPDX-License-Identifier: MPL-2.0
package io.advancex.engine.profiles

import io.advancex.engine.widescreen.WidescreenSpec
import kotlinx.serialization.Serializable

/**
 * A game profile (`profile.json`). See docs/GAME_PROFILES.md.
 *
 * ```json
 * {
 *   "name": "Example Game",
 *   "gameCode": "XXXX",
 *   "romHash": "...",
 *   "enhancements": { "shader": true, "spriteReplacement": false, "widescreen": false }
 * }
 * ```
 */
@Serializable
data class GameProfile(
    val schemaVersion: Int = 1,
    /** Defaults to the profile's directory name. */
    val id: String = "",
    val name: String,
    val description: String? = null,
    val authors: List<String> = emptyList(),
    /** Shorthand for [match].gameCodes. */
    val gameCode: String? = null,
    /** Shorthand hash (CRC32, SHA-1 or SHA-256, detected by length). */
    val romHash: String? = null,
    val match: MatchRules = MatchRules(),
    val enhancements: EnhancementFlags = EnhancementFlags(),
    val graphics: GraphicsHints? = null,
    /** Patch files, relative to the profile directory. */
    val patches: List<String> = emptyList(),
    /** Replacement pack files, relative to the profile directory. */
    val replacements: List<String> = emptyList(),
    /** Audio replacement packs (format reserved; playback coming later). */
    val audio: List<String> = emptyList(),
    val widescreen: WidescreenSpec? = null,
) {
    /** All hashes this profile is pinned to. */
    val allHashes: List<String>
        get() = (listOfNotNull(romHash) + match.sha256 + match.sha1 + match.crc32).map { it.trim().lowercase() }

    /** All game codes this profile applies to. */
    val allGameCodes: List<String>
        get() = (listOfNotNull(gameCode) + match.gameCodes).map { it.trim().uppercase() }.filter { it.isNotEmpty() }
}

@Serializable
data class MatchRules(
    val sha256: List<String> = emptyList(),
    val sha1: List<String> = emptyList(),
    val crc32: List<String> = emptyList(),
    val gameCodes: List<String> = emptyList(),
    /** Exact ROM size in bytes, if the profile is size-specific. */
    val romSize: Long? = null,
    /** Header software revision (0xBC). */
    val revision: Int? = null,
    /** Region name as reported by GbaRegion (e.g. "USA"); informational filter. */
    val region: String? = null,
)

@Serializable
data class EnhancementFlags(
    val shader: Boolean = false,
    val spriteReplacement: Boolean = false,
    val widescreen: Boolean = false,
    val runtimePatches: Boolean = false,
    val audioReplacement: Boolean = false,
)

@Serializable
data class GraphicsHints(
    /** Id of a built-in preset (see ShaderPresets), e.g. "lcd" or "crisp". */
    val preset: String? = null,
)
