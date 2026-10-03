// SPDX-License-Identifier: MPL-2.0
package io.advancex.engine.mods

import io.advancex.core.rom.RomIdentity
import io.advancex.engine.AdvanceJson
import io.advancex.engine.AdvanceX
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * `manifest.json` at the root of an `.advx` package.
 *
 * ```json
 * {
 *   "name": "Example Enhancement Pack",
 *   "version": "1.0.0",
 *   "author": "Example",
 *   "compatibleGames": [],
 *   "advanceXVersion": ">=0.1.0"
 * }
 * ```
 * `compatibleGames` entries can be a game code ("AXVE"), a hash, or an
 * object `{ "gameCode": ..., "sha1": ..., "crc32": ..., "name": ... }`.
 * An empty list means the mod is not game-specific (e.g. a shader pack).
 */
data class ModManifest(
    val id: String,
    val name: String,
    val version: SemVer,
    val author: String,
    val description: String,
    val license: String?,
    val compatibleGames: List<GameRef>,
    val advanceXVersion: VersionConstraint,
    val formatVersion: Int,
) {
    val isUniversal: Boolean get() = compatibleGames.isEmpty()

    fun isCompatibleWith(identity: RomIdentity): Boolean = isUniversal || compatibleGames.any { it.matches(identity) }

    fun supportsThisAdvanceX(): Boolean = advanceXVersion.isSatisfiedBy(SemVer.parse(AdvanceX.VERSION)!!)

    companion object {
        /** Parses and validates a manifest. Throws [ModException] with a user-facing message. */
        fun parse(text: String): ModManifest {
            val root = runCatching { AdvanceJson.parseToJsonElement(text).jsonObject }
                .getOrElse { throw ModException("manifest.json is not valid JSON.") }
            fun str(key: String): String? = (root[key] as? JsonPrimitive)?.contentOrNull?.trim()

            val name = str("name")?.takeIf { it.isNotEmpty() } ?: throw ModException("manifest.json has no \"name\".")
            val versionText = str("version") ?: throw ModException("manifest.json has no \"version\".")
            val version = SemVer.parse(versionText) ?: throw ModException("Invalid version \"$versionText\" (use e.g. 1.0.0).")
            val constraintText = str("advanceXVersion") ?: "*"
            val constraint = VersionConstraint.parse(constraintText)
                ?: throw ModException("Invalid advanceXVersion \"$constraintText\".")
            val id = (str("id") ?: slug(name)).also {
                if (!it.matches(ID_PATTERN)) throw ModException("Invalid mod id \"$it\".")
            }
            val games = when (val g = root["compatibleGames"]) {
                null -> emptyList()
                is JsonArray -> g.map { GameRef.fromJson(it) ?: throw ModException("Invalid entry in compatibleGames: $it") }
                else -> throw ModException("\"compatibleGames\" must be a list.")
            }
            val format = (root["formatVersion"] as? JsonPrimitive)?.intOrNull ?: 1
            if (format > AdvanceX.MOD_FORMAT_VERSION) {
                throw ModException("This mod needs a newer version of AdvanceX (format $format).")
            }
            return ModManifest(
                id = id,
                name = name.take(80),
                version = version,
                author = str("author").orEmpty().take(80),
                description = str("description").orEmpty().take(2000),
                license = str("license"),
                compatibleGames = games,
                advanceXVersion = constraint,
                formatVersion = format,
            )
        }

        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")

        fun slug(name: String): String =
            name.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
                .replace(Regex("-+"), "-").trim('-').take(64).ifEmpty { "mod" }
    }
}

/** Reference to a game a mod supports. */
@Serializable
data class GameRef(
    val gameCode: String? = null,
    val sha1: String? = null,
    val sha256: String? = null,
    val crc32: String? = null,
    val name: String? = null,
) {
    fun matches(identity: RomIdentity): Boolean {
        val hashes = listOfNotNull(sha1, sha256, crc32)
        if (hashes.isNotEmpty()) return hashes.any { identity.hashes.matches(it) }
        return gameCode != null && gameCode.equals(identity.header.gameCode, ignoreCase = true)
    }

    fun describe(): String = name ?: gameCode ?: (sha1 ?: sha256 ?: crc32)?.take(12) ?: "?"

    companion object {
        fun fromJson(element: kotlinx.serialization.json.JsonElement): GameRef? = when (element) {
            is JsonPrimitive -> element.contentOrNull?.trim()?.let { s ->
                when (s.length) {
                    4 -> GameRef(gameCode = s.uppercase())
                    8 -> GameRef(crc32 = s.lowercase())
                    40 -> GameRef(sha1 = s.lowercase())
                    64 -> GameRef(sha256 = s.lowercase())
                    else -> null
                }
            }
            is JsonObject -> runCatching { AdvanceJson.decodeFromJsonElement(serializer(), element) }.getOrNull()
                ?.takeIf { it.gameCode != null || it.sha1 != null || it.sha256 != null || it.crc32 != null }
            else -> null
        }
    }
}

class ModException(message: String) : Exception(message)
