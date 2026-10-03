// SPDX-License-Identifier: MPL-2.0
package io.advancex.core.saves

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One save-state slot as shown in the UI. */
data class StateSlotInfo(
    val slot: StateSlot,
    val file: File,
    val screenshot: File?,
    val header: StateFileHeader?,
) {
    val exists: Boolean get() = header != null
}

data class CartridgeBackup(val file: File, val createdAtMs: Long, val size: Long)

/**
 * Save management that never loses data silently:
 * - cartridge saves are snapshotted into rotating backups before each session
 * - restoring a backup first backs up the current save
 * - deleting a state keeps nothing but is an explicit user action
 * - everything can be exported to a single ZIP file
 */
class SaveManager(private val layout: SaveLayout, private val maxCartridgeBackups: Int = 5) {

    fun slots(gameId: String): List<StateSlotInfo> =
        (listOf(StateSlot.AUTO) + StateSlot.manual).map { slot ->
            val file = layout.stateFile(gameId, slot)
            val shot = layout.screenshotFile(gameId, slot).takeIf { it.isFile }
            StateSlotInfo(slot, file, shot, StateFileHeader.read(file))
        }

    fun deleteState(gameId: String, slot: StateSlot) {
        layout.stateFile(gameId, slot).delete()
        File(layout.stateFile(gameId, slot).path + ".bak").delete()
        layout.screenshotFile(gameId, slot).delete()
    }

    /**
     * Copies the current cartridge save into `backups/` (if it changed since
     * the newest backup) and prunes old copies. Call before starting a game.
     */
    fun snapshotCartridge(gameId: String, nowMs: Long = System.currentTimeMillis()): CartridgeBackup? {
        val save = layout.cartridgeSave(gameId)
        if (!save.isFile || save.length() == 0L) return null
        val data = save.readBytes()
        val dir = layout.cartridgeBackupsDir(gameId)
        dir.mkdirs()
        val newest = cartridgeBackups(gameId).firstOrNull()
        if (newest != null && newest.file.readBytes().contentEquals(data)) return newest
        val target = File(dir, "game-${stamp(nowMs)}.sav")
        AtomicFiles.write(target, data)
        target.setLastModified(nowMs)
        cartridgeBackups(gameId).drop(maxCartridgeBackups).forEach { it.file.delete() }
        return CartridgeBackup(target, nowMs, data.size.toLong())
    }

    /** Newest first. */
    fun cartridgeBackups(gameId: String): List<CartridgeBackup> =
        layout.cartridgeBackupsDir(gameId).listFiles { f -> f.isFile && f.name.endsWith(".sav") }
            ?.map { CartridgeBackup(it, it.lastModified(), it.length()) }
            ?.sortedWith(compareByDescending<CartridgeBackup> { it.createdAtMs }.thenByDescending { it.file.name })
            ?: emptyList()

    /** Replaces the cartridge save with [backup], keeping the current one as a backup first. */
    @Throws(IOException::class)
    fun restoreCartridgeBackup(gameId: String, backup: CartridgeBackup) {
        // Read first: snapshotting may rotate out the very backup being restored.
        val data = backup.file.readBytes()
        snapshotCartridge(gameId)
        AtomicFiles.write(layout.cartridgeSave(gameId), data, keepBackup = true)
    }

    /** Imports an external .sav (e.g. from desktop mGBA), keeping the current save as a backup. */
    @Throws(IOException::class)
    fun importCartridgeSave(gameId: String, data: ByteArray) {
        require(data.isNotEmpty() && data.size <= 256 * 1024) { "Not a GBA save file (unexpected size)." }
        layout.ensureDirs(gameId)
        snapshotCartridge(gameId)
        AtomicFiles.write(layout.cartridgeSave(gameId), data, keepBackup = true)
    }

    /** Writes every save file of a game into a ZIP archive. */
    @Throws(IOException::class)
    fun exportZip(gameId: String, output: OutputStream) {
        val base = layout.gameDir(gameId)
        ZipOutputStream(output).use { zip ->
            base.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { file ->
                val name = SaveLayout.sanitize(gameId) + "/" + file.relativeTo(base).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun stamp(ms: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(ms))
}
