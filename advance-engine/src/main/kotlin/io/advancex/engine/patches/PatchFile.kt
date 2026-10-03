// SPDX-License-Identifier: MPL-2.0
package io.advancex.engine.patches

import kotlinx.serialization.Serializable

/**
 * A runtime patch file (`patches/<name>.json` inside a profile or mod).
 *
 * ```json
 * {
 *   "schemaVersion": 1,
 *   "patches": [
 *     { "id": "infinite-lives", "name": "Infinite lives", "type": "memory",
 *       "address": "0x02000000", "bytes": "09 00 00 00", "mode": "everyFrame" },
 *     { "id": "rom-text", "name": "ROM text", "type": "rom",
 *       "offset": "0x40B", "bytes": "4F 4E 21", "expect": "4F 46 46" }
 *   ]
 * }
 * ```
 */
@Serializable
data class PatchFile(
    val schemaVersion: Int = 1,
    val patches: List<PatchDefinition> = emptyList(),
)

@Serializable
data class PatchDefinition(
    val id: String,
    val name: String = "",
    val description: String = "",
    /** "memory" (applied to emulated RAM) or "rom" (applied to the in-memory ROM copy). */
    val type: String = "memory",
    /** Memory patches: GBA bus address, e.g. "0x02000000". */
    val address: String? = null,
    /** ROM patches: file offset, e.g. "0x40B". */
    val offset: String? = null,
    /** Hex bytes to write, e.g. "09 00 00 00". */
    val bytes: String,
    /** Optional original bytes; the patch only applies where they match. */
    val expect: String? = null,
    /** "everyFrame" (default) or "once". Memory patches only. */
    val mode: String = "everyFrame",
    val condition: PatchConditionDefinition? = null,
    val enabledByDefault: Boolean = true,
)

@Serializable
data class PatchConditionDefinition(
    val address: String,
    /** 1, 2 or 4 bytes. */
    val width: Int = 1,
    /** One of ==, !=, <, >. */
    val op: String = "==",
    val value: Long = 0,
)
