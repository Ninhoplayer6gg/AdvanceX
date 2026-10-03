// SPDX-License-Identifier: MPL-2.0
package io.advancex.engine.mods

import io.advancex.engine.content.ContentPaths
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** What a package contains, for display before/after installation. */
data class ModContents(
    val profiles: Int = 0,
    val patches: Int = 0,
    val textures: Int = 0,
    val sprites: Int = 0,
    val audio: Int = 0,
    val shaders: Int = 0,
    val scripts: Int = 0,
    val totalBytes: Long = 0,
) {
    /** Scripts are never executed by this version of AdvanceX. */
    val containsScripts: Boolean get() = scripts > 0
}

/**
 * Reads and validates `.advx` packages (ZIP containers).
 *
 * Third-party archives are untrusted input. Defences:
 * - entries must be relative, normalized paths (no `..`, no absolute paths)
 * - only the documented top-level folders are extracted
 * - limits on entry count, file size, total size and compression ratio
 *   (zip bombs)
 * - nothing is executed: `scripts/` is stored but never run
 */
object ModArchive {
    const val MANIFEST = "manifest.json"
    val ALLOWED_DIRS = setOf("profiles", "patches", "textures", "sprites", "audio", "shaders", "scripts", "docs")
    private val ALLOWED_ROOT_FILES = Regex("(?i)(manifest\\.json|readme(\\.md|\\.txt)?|license(\\.md|\\.txt)?|preview\\.png)")

    const val MAX_ENTRIES = 20_000
    const val MAX_FILE_BYTES = 64L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    const val MAX_RATIO = 200

    data class Inspection(val manifest: ModManifest, val contents: ModContents, val ignored: List<String>)

    /** Validates [file] without extracting it. Throws [ModException]. */
    fun inspect(file: File): Inspection = open(file) { zip -> scan(zip, extractTo = null) }

    /** Validates and extracts [file] into [target] (which must not exist yet). */
    fun extract(file: File, target: File): Inspection {
        if (target.exists()) throw ModException("Internal error: extraction target already exists.")
        return open(file) { zip -> scan(zip, extractTo = target) }
    }

    private fun <T> open(file: File, block: (ZipFile) -> T): T {
        val zip = try {
            ZipFile(file)
        } catch (e: IOException) {
            throw ModException("This file is not a valid .advx package (not a ZIP archive).")
        }
        return zip.use(block)
    }

    private fun scan(zip: ZipFile, extractTo: File?): Inspection {
        val entries = zip.entries().toList()
        if (entries.size > MAX_ENTRIES) throw ModException("Package has too many files (${entries.size}).")
        var manifestText: String? = null
        var total = 0L
        var contents = ModContents()
        val ignored = mutableListOf<String>()

        for (entry in entries) {
            if (entry.isDirectory) continue
            val path = ContentPaths.normalize(entry.name)
                ?: throw ModException("Package contains an unsafe path: ${entry.name}")
            val top = path.substringBefore('/')
            val isRootFile = !path.contains('/')
            if (isRootFile && !ALLOWED_ROOT_FILES.matches(path)) {
                ignored += path
                continue
            }
            if (!isRootFile && top !in ALLOWED_DIRS) {
                ignored += path
                continue
            }
            val declared = entry.size
            if (declared > MAX_FILE_BYTES) throw ModException("File too large in package: $path")
            if (entry.compressedSize > 0 && declared > 0 && declared / entry.compressedSize > MAX_RATIO && declared > 1_000_000) {
                throw ModException("Suspicious compression ratio for $path; package rejected.")
            }

            // Read with a hard cap: declared sizes in ZIP headers can lie.
            val data = zip.getInputStream(entry).use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    read += n
                    if (read > MAX_FILE_BYTES || total + read > MAX_TOTAL_BYTES) {
                        throw ModException("Package is too large once decompressed.")
                    }
                    buffer.write(chunk, 0, n)
                }
                buffer.toByteArray()
            }
            total += data.size

            if (path == MANIFEST) manifestText = data.toString(Charsets.UTF_8)
            contents = when (top) {
                "profiles" -> if (path.endsWith("/profile.json")) contents.copy(profiles = contents.profiles + 1) else contents
                "patches" -> contents.copy(patches = contents.patches + 1)
                "textures" -> contents.copy(textures = contents.textures + 1)
                "sprites" -> contents.copy(sprites = contents.sprites + 1)
                "audio" -> contents.copy(audio = contents.audio + 1)
                "shaders" -> contents.copy(shaders = contents.shaders + 1)
                "scripts" -> contents.copy(scripts = contents.scripts + 1)
                else -> contents
            }
            if (extractTo != null) {
                val out = File(extractTo, path)
                out.parentFile?.mkdirs()
                out.writeBytes(data)
            }
        }
        val manifest = ModManifest.parse(manifestText ?: throw ModException("Package has no manifest.json."))
        return Inspection(manifest, contents.copy(totalBytes = total), ignored)
    }
}
