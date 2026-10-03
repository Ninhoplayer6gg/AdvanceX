// SPDX-License-Identifier: MPL-2.0
package io.advancex.engine.safety

import io.advancex.engine.AdvanceJson
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Detects sessions that ended abnormally (native crash, ANR kill) while
 * Advance Engine features were active, so the app can fall back to the
 * original emulation for that game:
 *
 *   enhancement breaks a game -> crash -> next launch: enhancements disabled
 *   for that game -> plain emulation
 *
 * A marker file is written when a session starts and removed when it ends
 * normally. If the marker is still there on the next launch, the previous
 * session did not end cleanly.
 */
class EnhancementGuard(private val markerFile: File) {
    @Serializable
    data class Marker(
        val gameId: String,
        val advanceMode: Boolean,
        val features: List<String>,
        val startedAtMs: Long,
    )

    fun sessionStarted(marker: Marker) {
        markerFile.parentFile?.mkdirs()
        val tmp = File(markerFile.path + ".tmp")
        tmp.writeText(AdvanceJson.encodeToString(Marker.serializer(), marker))
        tmp.renameTo(markerFile)
    }

    fun sessionEnded() {
        markerFile.delete()
    }

    /**
     * Returns the marker of a session that did not end cleanly (and clears
     * it), or null. Call once at app start.
     */
    fun consumeUncleanExit(): Marker? {
        if (!markerFile.isFile) return null
        val marker = runCatching { AdvanceJson.decodeFromString(Marker.serializer(), markerFile.readText()) }.getOrNull()
        markerFile.delete()
        return marker
    }

    companion object {
        /** Whether an unclean exit should put the game into safe mode. */
        fun shouldEnterSafeMode(marker: Marker): Boolean = marker.advanceMode && marker.features.isNotEmpty()
    }
}
