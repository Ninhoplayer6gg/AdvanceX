// SPDX-License-Identifier: MPL-2.0
package io.advancex.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.advancex.core.emulator.Log
import io.advancex.core.rom.RomCheck
import io.advancex.core.rom.RomIdentifier
import io.advancex.core.rom.RomIdentity
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

sealed class ImportResult {
    data class Added(val entry: GameEntry, val warnings: List<String>) : ImportResult()
    data class AlreadyPresent(val entry: GameEntry) : ImportResult()
    data class Failed(val name: String, val message: String) : ImportResult()
}

/**
 * Imports user-provided ROMs (picked with the Storage Access Framework).
 *
 * The file is copied into app storage so it stays available, identified by
 * hash, and added to the library. `.zip` archives containing a `.gba` file
 * are supported. The original file is never modified.
 */
class RomImporter(private val context: Context, private val library: GameLibrary) {

    /** Must be called off the main thread. */
    fun import(uri: Uri): ImportResult {
        val name = displayName(uri) ?: uri.lastPathSegment ?: "game"
        return try {
            val stream = context.contentResolver.openInputStream(uri) ?: return ImportResult.Failed(name, "Cannot open the file.")
            stream.use { importStream(it, name, isDemo = false) }
        } catch (e: SecurityException) {
            ImportResult.Failed(name, "AdvanceX was not allowed to read this file.")
        } catch (e: IOException) {
            Log.w(TAG, "Import of $name failed", e)
            ImportResult.Failed(name, "The file could not be read (${e.message}).")
        }
    }

    /** Adds the bundled AdvanceX Test Cartridge (homebrew, freely distributable). */
    fun installDemo(): ImportResult =
        context.assets.open("demo/$DEMO_ROM").use { importStream(it, "AdvanceX Test Cartridge.gba", isDemo = true) }

    private fun importStream(input: InputStream, name: String, isDemo: Boolean): ImportResult {
        val tmp = File.createTempFile("import", ".gba", context.cacheDir)
        try {
            val buffered = input.buffered()
            buffered.mark(4)
            val magic = ByteArray(4)
            val read = buffered.read(magic)
            buffered.reset()
            val isZip = read == 4 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() &&
                magic[2].toInt() == 3 && magic[3].toInt() == 4
            var displayName = name
            if (isZip) {
                val inner = extractFirstGba(buffered, tmp) ?: return ImportResult.Failed(name, "The ZIP archive does not contain a .gba file.")
                displayName = inner
            } else {
                copyLimited(buffered, tmp)
            }
            val check = when (val result = RomIdentifier.identify(tmp)) {
                is RomCheck.Rejected -> return ImportResult.Failed(name, result.reason.message)
                is RomCheck.Ok -> result
            }
            val id = check.identity
            library.get(id.gameId)?.let { return ImportResult.AlreadyPresent(it) }

            val target = File(library.romsDir, "${id.gameId}.gba")
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
            }
            val entry = entryFor(id, displayName, target.name, isDemo)
            library.add(entry)
            Log.i(TAG, "Imported ${entry.title} (${entry.id})")
            return ImportResult.Added(entry, check.warnings.map { it.message })
        } catch (e: TooLarge) {
            return ImportResult.Failed(name, "The file is larger than 32 MiB, the maximum size of a GBA cartridge.")
        } finally {
            tmp.delete()
        }
    }

    private fun entryFor(id: RomIdentity, fileName: String, romFile: String, isDemo: Boolean) = GameEntry(
        id = id.gameId,
        title = if (isDemo) "AdvanceX Test Cartridge" else prettyTitle(fileName, id.displayTitle),
        headerTitle = id.header.title,
        gameCode = id.header.gameCode,
        makerCode = id.header.makerCode,
        version = id.header.version,
        region = id.header.region.displayName,
        crc32 = id.hashes.crc32,
        sha1 = id.hashes.sha1,
        sha256 = id.hashes.sha256,
        size = id.hashes.size,
        romFile = romFile,
        addedAtMs = System.currentTimeMillis(),
        isDemo = isDemo,
    )

    private fun extractFirstGba(input: InputStream, target: File): String? {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (!entry.isDirectory && entry.name.lowercase().endsWith(".gba")) {
                    copyLimited(zip, target)
                    return entry.name.substringAfterLast('/')
                }
            }
        }
    }

    private class TooLarge : IOException()

    private fun copyLimited(input: InputStream, target: File) {
        target.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > RomIdentifier.MAX_ROM_SIZE) throw TooLarge()
                out.write(buffer, 0, n)
            }
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    companion object {
        private const val TAG = "Import"
        const val DEMO_ROM = "advancex-testcart.gba"

        /** "Some Game (USA) [!].gba" -> "Some Game". Falls back to the header title. */
        fun prettyTitle(fileName: String, fallback: String): String {
            val base = fileName.substringBeforeLast('.')
                .replace(Regex("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]"), "")
                .replace('_', ' ')
                .trim()
            return base.ifBlank { fallback }.take(80)
        }
    }
}
