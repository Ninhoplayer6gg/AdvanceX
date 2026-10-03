// SPDX-License-Identifier: MPL-2.0
package io.advancex.engine

import io.advancex.core.emulator.Log
import io.advancex.core.emulator.VideoSettings
import io.advancex.core.rom.RomIdentity
import io.advancex.engine.content.ContentRoot
import io.advancex.engine.graphics.ShaderPresets
import io.advancex.engine.patches.PatchBlobEncoder
import io.advancex.engine.patches.PatchDefinition
import io.advancex.engine.patches.PatchFile
import io.advancex.engine.patches.PatchIssue
import io.advancex.engine.patches.PatchValidator
import io.advancex.engine.patches.RuntimePatch
import io.advancex.engine.profiles.MatchConfidence
import io.advancex.engine.profiles.ProfileMatch
import io.advancex.engine.profiles.ProfileRepository
import io.advancex.engine.replacements.ReplacementEntry
import io.advancex.engine.replacements.ReplacementPacks
import io.advancex.engine.widescreen.WidescreenDecision
import io.advancex.engine.widescreen.WidescreenMode
import io.advancex.engine.widescreen.WidescreenResolver

data class EngineRequest(
    val identity: RomIdentity,
    val advanceMode: Boolean,
    /** Per-game user choices for individual patches (id -> enabled). */
    val patchToggles: Map<String, Boolean> = emptyMap(),
    val replacementsEnabled: Boolean = true,
    val widescreen: WidescreenMode = WidescreenMode.OFF,
)

data class EngineMessage(val warning: Boolean, val text: String)

/** Everything the Advance Engine will do for one session. */
class EnginePlan(
    val advanceMode: Boolean,
    val match: ProfileMatch?,
    val patches: List<RuntimePatch>,
    val patchIssues: List<PatchIssue>,
    val patchBlob: ByteArray,
    val replacements: List<ReplacementEntry>,
    val videoHints: VideoSettings?,
    val widescreen: WidescreenDecision,
    val messages: List<EngineMessage>,
) {
    /** Feature names recorded by the EnhancementGuard crash marker. */
    val activeFeatures: List<String>
        get() = buildList {
            if (patches.any { it.enabled }) add("patches")
            if (replacements.isNotEmpty()) add("replacements")
            if (videoHints != null) add("profile-graphics")
            if (widescreen.mode != WidescreenMode.OFF) add("widescreen")
        }
}

/**
 * Entry point of the Advance Engine. Given a ROM identity and the user's
 * choices, decides which profile, patches, replacements and presets apply.
 *
 * The engine never touches the emulator directly; the app passes the plan to
 * the native session. With Advance Mode off the plan is informational only.
 */
class AdvanceEngine(private val contentRoots: (RomIdentity) -> List<ContentRoot>) {

    fun plan(request: EngineRequest): EnginePlan {
        val repository = ProfileRepository(contentRoots(request.identity))
        val match = repository.match(request.identity)
        val messages = mutableListOf<EngineMessage>()
        repository.errors.forEach { messages += EngineMessage(true, "Profile ${it.path}: ${it.message}") }

        if (!request.advanceMode) {
            if (match != null) {
                messages += EngineMessage(false, "Advance profile available: ${match.profile.name}. Turn on Advance Mode to use it.")
            }
            return EnginePlan(
                advanceMode = false, match = match, patches = emptyList(), patchIssues = emptyList(),
                patchBlob = ByteArray(0), replacements = emptyList(), videoHints = null,
                widescreen = WidescreenResolver.resolve(WidescreenMode.OFF, false, null), messages = messages,
            )
        }

        var hints: VideoSettings? = null
        var patches = emptyList<RuntimePatch>()
        var issues = emptyList<PatchIssue>()
        val replacements = mutableListOf<ReplacementEntry>()

        if (match != null) {
            val p = match.profile
            messages += EngineMessage(false, "Using profile \"${p.name}\" (${match.confidence.description}).")
            if (p.enhancements.shader) {
                hints = ShaderPresets.byId(p.graphics?.preset)?.video
                if (hints == null && p.graphics?.preset != null) {
                    messages += EngineMessage(true, "Unknown graphics preset '${p.graphics.preset}'.")
                }
            }
            if (p.enhancements.runtimePatches && p.patches.isNotEmpty()) {
                if (match.confidence != MatchConfidence.EXACT) {
                    messages += EngineMessage(
                        true,
                        "Runtime patches are disabled: they require an exact ROM match and this ROM was only matched by game code.",
                    )
                } else {
                    val definitions = loadPatchDefinitions(match, messages)
                    val validation = PatchValidator.validate(definitions, request.identity.hashes.size, request.patchToggles)
                    patches = validation.patches
                    issues = validation.issues
                    issues.forEach { messages += EngineMessage(true, "Patch '${it.patchId}' disabled: ${it.message}") }
                }
            }
            if (p.enhancements.spriteReplacement && request.replacementsEnabled) {
                for (pack in p.replacements) {
                    val path = match.loaded.path(pack)
                    if (path == null) {
                        messages += EngineMessage(true, "Invalid replacement path '$pack'.")
                        continue
                    }
                    val result = ReplacementPacks.load(match.loaded.root, path)
                    replacements += result.entries
                    result.problems.forEach { messages += EngineMessage(true, "Replacements: $it") }
                }
            }
        }

        val widescreen = WidescreenResolver.resolve(request.widescreen, true, match)
        widescreen.reason?.let { messages += EngineMessage(true, it) }

        return EnginePlan(
            advanceMode = true,
            match = match,
            patches = patches,
            patchIssues = issues,
            patchBlob = PatchBlobEncoder.encode(patches),
            replacements = replacements,
            videoHints = hints,
            widescreen = widescreen,
            messages = messages,
        )
    }

    private fun loadPatchDefinitions(match: ProfileMatch, messages: MutableList<EngineMessage>): List<PatchDefinition> {
        val out = mutableListOf<PatchDefinition>()
        for (file in match.profile.patches) {
            val path = match.loaded.path(file)
            val text = path?.let { match.loaded.root.readText(it) }
            if (text == null) {
                messages += EngineMessage(true, "Patch file '$file' not found.")
                continue
            }
            runCatching { AdvanceJson.decodeFromString(PatchFile.serializer(), text) }
                .onSuccess { pf ->
                    if (pf.schemaVersion > AdvanceX.PATCH_SCHEMA_VERSION) {
                        messages += EngineMessage(true, "Patch file '$file' needs a newer AdvanceX.")
                    } else {
                        out += pf.patches
                    }
                }
                .onFailure {
                    Log.w(TAG, "Invalid patch file $file", it)
                    messages += EngineMessage(true, "Patch file '$file' is not valid JSON.")
                }
        }
        return out
    }

    companion object {
        private const val TAG = "AdvanceEngine"
    }
}
