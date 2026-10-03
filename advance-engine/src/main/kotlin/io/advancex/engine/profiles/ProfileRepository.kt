// SPDX-License-Identifier: MPL-2.0
package io.advancex.engine.profiles

import io.advancex.core.emulator.Log
import io.advancex.core.rom.RomIdentity
import io.advancex.engine.AdvanceJson
import io.advancex.engine.content.ContentOrigin
import io.advancex.engine.content.ContentRoot

/** How strongly a profile matched a ROM. */
enum class MatchConfidence(val description: String) {
    /** Hash match: this exact ROM image. Required for runtime patches. */
    EXACT("Exact ROM match (hash verified)"),
    /** Game code plus every size/revision constraint the profile declares. */
    GAME_CODE("Matched by game code"),
}

/** A profile loaded from a content root, with its location. */
data class LoadedProfile(
    val profile: GameProfile,
    val root: ContentRoot,
    /** Directory of profile.json inside [root], e.g. "profiles/my_game". */
    val directory: String,
) {
    val origin: ContentOrigin get() = root.origin
    fun path(relative: String): String? = io.advancex.engine.content.ContentPaths.sibling("$directory/profile.json", relative)
}

data class ProfileMatch(val loaded: LoadedProfile, val confidence: MatchConfidence) {
    val profile: GameProfile get() = loaded.profile
}

data class ProfileLoadError(val root: ContentOrigin, val path: String, val message: String)

/**
 * Loads profiles from any number of content roots (bundled assets, enabled
 * mods, user folder) and finds the best one for a ROM.
 *
 * Each root is scanned at `profiles/<profile-id>/profile.json`.
 */
class ProfileRepository(roots: List<ContentRoot>) {
    val profiles: List<LoadedProfile>
    val errors: List<ProfileLoadError>

    init {
        val loaded = mutableListOf<LoadedProfile>()
        val errs = mutableListOf<ProfileLoadError>()
        for (root in roots) {
            for (dirName in root.list(PROFILES_DIR)) {
                val dir = "$PROFILES_DIR/$dirName"
                val text = root.readText("$dir/profile.json") ?: continue
                runCatching { AdvanceJson.decodeFromString(GameProfile.serializer(), text) }
                    .onSuccess { p ->
                        val profile = if (p.id.isBlank()) p.copy(id = dirName) else p
                        val problem = validate(profile)
                        if (problem != null) {
                            errs += ProfileLoadError(root.origin, dir, problem)
                        } else {
                            loaded += LoadedProfile(profile, root, dir)
                        }
                    }
                    .onFailure { e -> errs += ProfileLoadError(root.origin, dir, "Invalid JSON: ${e.message}") }
            }
        }
        profiles = loaded
        errors = errs
        errors.forEach { Log.w(TAG, "Profile ${it.path} (${it.root}) skipped: ${it.message}") }
    }

    /** Best profile for [identity], or null. Exact hash beats game code; user beats mod beats bundled. */
    fun match(identity: RomIdentity): ProfileMatch? =
        profiles.mapNotNull { lp -> confidence(lp.profile, identity)?.let { ProfileMatch(lp, it) } }
            .sortedWith(
                compareBy<ProfileMatch> { it.confidence.ordinal }
                    .thenByDescending { it.loaded.origin.priority },
            )
            .firstOrNull()

    fun byId(id: String): LoadedProfile? = profiles.firstOrNull { it.profile.id == id }

    companion object {
        const val PROFILES_DIR = "profiles"
        private const val TAG = "Profiles"

        fun confidence(profile: GameProfile, identity: RomIdentity): MatchConfidence? {
            val hashes = profile.allHashes
            if (hashes.isNotEmpty()) {
                // A hash-pinned profile only ever applies to that exact image.
                return if (hashes.any { identity.hashes.matches(it) }) MatchConfidence.EXACT else null
            }
            val code = identity.header.gameCode.uppercase()
            if (code.isEmpty() || code !in profile.allGameCodes) return null
            val rules = profile.match
            if (rules.romSize != null && rules.romSize != identity.hashes.size) return null
            if (rules.revision != null && rules.revision != identity.header.version) return null
            if (rules.region != null && !rules.region.equals(identity.header.region.displayName, ignoreCase = true)) {
                return null
            }
            return MatchConfidence.GAME_CODE
        }

        fun validate(profile: GameProfile): String? {
            if (profile.schemaVersion > io.advancex.engine.AdvanceX.PROFILE_SCHEMA_VERSION) {
                return "Profile schema ${profile.schemaVersion} is newer than this AdvanceX supports"
            }
            if (profile.name.isBlank()) return "Profile has no name"
            if (!profile.id.matches(Regex("[A-Za-z0-9._-]{1,64}"))) return "Invalid profile id '${profile.id}'"
            for (h in profile.allHashes) {
                if (h.length !in setOf(8, 40, 64) || !h.all { it in '0'..'9' || it in 'a'..'f' }) {
                    return "Invalid hash '$h' (expected CRC32, SHA-1 or SHA-256 in hex)"
                }
            }
            for (c in profile.allGameCodes) {
                if (c.length != 4) return "Invalid game code '$c' (expected 4 characters)"
            }
            if (profile.allHashes.isEmpty() && profile.allGameCodes.isEmpty()) {
                return "Profile must declare a ROM hash or a game code"
            }
            return null
        }
    }
}
