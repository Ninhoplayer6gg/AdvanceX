// SPDX-License-Identifier: MPL-2.0
package io.advancex.engine

import kotlinx.serialization.json.Json

/** Version information used for compatibility checks (e.g. `.advx` mods). */
object AdvanceX {
    const val VERSION = "0.1.0"
    const val PROFILE_SCHEMA_VERSION = 1
    const val PATCH_SCHEMA_VERSION = 1
    const val MOD_FORMAT_VERSION = 1
}

/**
 * JSON settings shared by every Advance Engine format: unknown keys are
 * ignored (forward compatibility), comments-free strict JSON otherwise.
 */
internal val AdvanceJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
    explicitNulls = false
}
