// SPDX-License-Identifier: MPL-2.0
package io.advancex.engine.widescreen

import io.advancex.core.emulator.BackgroundStyle
import io.advancex.engine.graphics.FeatureStatus
import io.advancex.engine.profiles.MatchConfidence
import io.advancex.engine.profiles.ProfileMatch
import kotlinx.serialization.Serializable

/**
 * Widescreen strategies. The 240×160 image is never simply stretched.
 *
 * - [DISPLAY]: the original 3:2 image stays centred and pixel-correct; the
 *   extra width is used for interface/decoration (ambient glow today,
 *   artwork and widgets later). Works with every game.
 * - [NATIVE]: the game itself renders a wider view. This needs game-specific
 *   runtime patches (camera bounds, sprite culling, HUD placement) declared
 *   by a profile for an exact ROM. The framework is defined here; rendering
 *   a wider framebuffer is not implemented yet (Coming later).
 */
enum class WidescreenMode(val label: String, val status: FeatureStatus) {
    OFF("Off", FeatureStatus.STABLE),
    DISPLAY("Display widescreen", FeatureStatus.STABLE),
    NATIVE("Native widescreen", FeatureStatus.COMING_LATER),
}

/** Profile section describing game-specific widescreen support. */
@Serializable
data class WidescreenSpec(
    /** True only if the profile provides the patches needed for native widescreen. */
    val nativeSupported: Boolean = false,
    /** Internal render width the patches target (e.g. 320 for 16:9-ish at 160 lines). */
    val targetWidth: Int? = null,
    /** Patch files implementing the widened view, relative to the profile. */
    val patches: List<String> = emptyList(),
    val notes: String? = null,
)

data class WidescreenDecision(
    val mode: WidescreenMode,
    val background: BackgroundStyle?,
    val reason: String?,
)

object WidescreenResolver {
    fun resolve(requested: WidescreenMode, advanceMode: Boolean, match: ProfileMatch?): WidescreenDecision {
        if (requested == WidescreenMode.OFF) return WidescreenDecision(WidescreenMode.OFF, null, null)
        if (!advanceMode) {
            return WidescreenDecision(WidescreenMode.OFF, null, "Widescreen needs Advance Mode.")
        }
        if (requested == WidescreenMode.NATIVE) {
            val spec = match?.profile?.widescreen
            val reason = when {
                spec == null || !spec.nativeSupported -> "This game has no native widescreen profile."
                match.confidence != MatchConfidence.EXACT -> "Native widescreen requires an exact ROM match."
                else -> "Native widescreen rendering is coming later."
            }
            return WidescreenDecision(WidescreenMode.DISPLAY, BackgroundStyle.AMBIENT, "$reason Using display widescreen.")
        }
        return WidescreenDecision(WidescreenMode.DISPLAY, BackgroundStyle.AMBIENT, null)
    }
}
