// SPDX-License-Identifier: MPL-2.0
package io.advancex.engine.audio

import io.advancex.engine.graphics.FeatureStatus
import kotlinx.serialization.Serializable

/**
 * Audio replacement packs (format reserved; playback Coming later).
 *
 * Planned design: the native mixer gets extra streams next to the emulated
 * GBA output. A trigger (a known song id in RAM, or a memory value change)
 * starts an external track and ducks/mutes the original channels. Packs are
 * profile-specific because song ids are per game.
 *
 * ```json
 * {
 *   "schemaVersion": 1,
 *   "tracks": [
 *     { "trigger": { "address": "0x03001234", "width": 2, "value": 12 },
 *       "file": "audio/title_theme.ogg", "loop": true, "volume": 0.9,
 *       "muteOriginal": true }
 *   ]
 * }
 * ```
 */
@Serializable
data class AudioReplacementPack(
    val schemaVersion: Int = 1,
    val tracks: List<AudioTrackReplacement> = emptyList(),
)

@Serializable
data class AudioTrackReplacement(
    val trigger: AudioTrigger,
    val file: String,
    val loop: Boolean = true,
    val volume: Float = 1f,
    val muteOriginal: Boolean = true,
)

@Serializable
data class AudioTrigger(val address: String, val width: Int = 1, val value: Long)

/** Contract for the future implementation. */
interface AudioReplacementProvider {
    val status: FeatureStatus
    fun load(pack: AudioReplacementPack): Boolean
}

object AudioReplacement {
    val status = FeatureStatus.COMING_LATER
}
