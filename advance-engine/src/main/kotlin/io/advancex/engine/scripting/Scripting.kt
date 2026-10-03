// SPDX-License-Identifier: MPL-2.0
package io.advancex.engine.scripting

import io.advancex.engine.graphics.FeatureStatus

/**
 * Mod scripting — designed, deliberately not implemented in 0.x.
 *
 * Third-party code is never executed by this version of AdvanceX. Mods may
 * ship a `scripts/` folder (it is stored and listed), but nothing in it runs.
 *
 * When scripting arrives it must run inside a sandbox with this policy:
 * - an embedded interpreter (no JIT, no native code loading, no reflection)
 * - API limited to: read memory, request patches through the PatchEngine
 *   (same validation as JSON patches), draw overlays, log
 * - no file system, network, clipboard, contacts or other Android APIs
 * - instruction budget per frame; a script that exceeds it is stopped and
 *   disabled for the session, and the game continues unmodified
 * - memory cap per script
 */
data class SandboxPolicy(
    val maxInstructionsPerFrame: Long = 200_000,
    val maxMemoryBytes: Long = 8L * 1024 * 1024,
    val allowMemoryRead: Boolean = true,
    val allowPatchRequests: Boolean = true,
    val allowFileAccess: Boolean = false,
    val allowNetwork: Boolean = false,
)

interface ScriptEngine {
    val name: String
    val status: FeatureStatus
    val policy: SandboxPolicy
}

/** The only engine in 0.x: refuses to run anything. */
object NoScriptEngine : ScriptEngine {
    override val name = "Disabled"
    override val status = FeatureStatus.COMING_LATER
    override val policy = SandboxPolicy()
}
