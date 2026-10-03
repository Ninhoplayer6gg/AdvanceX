// SPDX-License-Identifier: MPL-2.0
package io.advancex.app.data

import io.advancex.core.emulator.Log
import io.advancex.core.rom.GbaHeader
import io.advancex.core.rom.RomHashes
import io.advancex.core.rom.RomIdentity
import io.advancex.core.saves.AtomicFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** One game in the library. */
@Serializable
data class GameEntry(
    val id: String,
    val title: String,
    val headerTitle: String,
    val gameCode: String,
    val makerCode: String,
    val version: Int,
    val region: String,
    val crc32: String,
    val sha1: String,
    val sha256: String,
    val size: Long,
    val romFile: String,
    val addedAtMs: Long,
    val lastPlayedAtMs: Long = 0,
    val playTimeMs: Long = 0,
    val favorite: Boolean = false,
    val hasCustomCover: Boolean = false,
    val isDemo: Boolean = false,
) {
    /** Rebuilds the identity used by the Advance Engine and save system. */
    fun identity(): RomIdentity = RomIdentity(
        GbaHeader(headerTitle, gameCode, makerCode, version, 0, checksumValid = true, fixedValueValid = true, entryIsBranch = true),
        RomHashes(crc32, sha1, sha256, size),
    )
}

/**
 * The game library, persisted as `library.json` (atomic writes). ROM files
 * live in `roms/`, covers in `covers/`. Mutations are synchronized; call
 * them off the main thread.
 */
class GameLibrary(private val root: File) {
    private val file = File(root, "library.json")
    val romsDir = File(root, "roms").apply { mkdirs() }
    val coversDir = File(root, "covers").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val listeners = mutableListOf<() -> Unit>()

    @Volatile
    var games: List<GameEntry> = load()
        private set

    private fun load(): List<GameEntry> {
        val data = AtomicFiles.readWithBackup(file) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(GameEntry.serializer()), data.data.toString(Charsets.UTF_8)) }
            .onFailure { Log.e(TAG, "Library file unreadable; starting empty (backup kept)", it) }
            .getOrDefault(emptyList())
            .filter { File(romsDir, it.romFile).isFile }
    }

    @Synchronized
    private fun save(list: List<GameEntry>) {
        AtomicFiles.writeText(file, json.encodeToString(ListSerializer(GameEntry.serializer()), list), keepBackup = true)
        games = list
        synchronized(listeners) { listeners.toList() }.forEach { it() }
    }

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }

    fun get(id: String): GameEntry? = games.firstOrNull { it.id == id }

    @Synchronized
    fun add(entry: GameEntry) = save(games.filterNot { it.id == entry.id } + entry)

    @Synchronized
    fun update(id: String, transform: (GameEntry) -> GameEntry) {
        val current = get(id) ?: return
        save(games.map { if (it.id == id) transform(current) else it })
    }

    /** Removes a game from the library. The ROM copy is deleted; saves are kept unless [deleteSaves]. */
    @Synchronized
    fun remove(id: String, savesDir: File?, deleteSaves: Boolean) {
        val entry = get(id) ?: return
        File(romsDir, entry.romFile).delete()
        coverFiles(entry).forEach { it.delete() }
        if (deleteSaves && savesDir != null) savesDir.deleteRecursively()
        save(games.filterNot { it.id == id })
    }

    fun romFile(entry: GameEntry) = File(romsDir, entry.romFile)
    fun customCover(entry: GameEntry) = File(coversDir, "${entry.id}-custom.png")
    fun autoCover(entry: GameEntry) = File(coversDir, "${entry.id}.png")

    /** Best available cover image, if any (custom > last screenshot). */
    fun coverFile(entry: GameEntry): File? =
        customCover(entry).takeIf { entry.hasCustomCover && it.isFile } ?: autoCover(entry).takeIf { it.isFile }

    private fun coverFiles(entry: GameEntry) = listOf(customCover(entry), autoCover(entry))

    fun recentlyPlayed(limit: Int = 10): List<GameEntry> =
        games.filter { it.lastPlayedAtMs > 0 }.sortedByDescending { it.lastPlayedAtMs }.take(limit)

    companion object {
        private const val TAG = "Library"
    }
}
