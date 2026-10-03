// SPDX-License-Identifier: MPL-2.0
package io.advancex.engine.mods

import io.advancex.core.emulator.Log
import io.advancex.core.rom.RomIdentity
import io.advancex.engine.AdvanceJson
import io.advancex.engine.content.ContentOrigin
import io.advancex.engine.content.ContentRoot
import io.advancex.engine.content.DirectoryContentRoot
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** An installed mod as listed in the mod manager. */
data class InstalledMod(
    val manifest: ModManifest,
    val contents: ModContents,
    val enabled: Boolean,
    val installedAtMs: Long,
    val directory: File,
) {
    val id: String get() = manifest.id
}

enum class Compatibility(val label: String) {
    COMPATIBLE("Compatible"),
    UNIVERSAL("Works with any game"),
    WRONG_GAME("Made for a different game"),
    NEEDS_NEWER_APP("Requires a newer AdvanceX"),
}

@Serializable
private data class Registry(val mods: List<RegistryEntry> = emptyList())

@Serializable
private data class RegistryEntry(val id: String, val enabled: Boolean, val installedAtMs: Long)

/**
 * Installs, enables, disables and removes `.advx` mods.
 *
 * Layout: `mods/<mod-id>/...` (extracted package) + `mods/registry.json`.
 * Installation extracts into a staging directory first and only then swaps
 * it in, so a failed or interrupted install never damages an existing mod.
 */
class ModManager(private val root: File) {
    private val registryFile = File(root, "registry.json")

    init {
        root.mkdirs()
        cleanupStaging()
    }

    fun installed(): List<InstalledMod> {
        val registry = readRegistry()
        return registry.mods.mapNotNull { entry ->
            val dir = File(root, entry.id)
            val manifestFile = File(dir, ModArchive.MANIFEST)
            if (!manifestFile.isFile) return@mapNotNull null
            runCatching {
                InstalledMod(ModManifest.parse(manifestFile.readText()), countContents(dir), entry.enabled, entry.installedAtMs, dir)
            }.onFailure { Log.w(TAG, "Installed mod ${entry.id} is unreadable", it) }.getOrNull()
        }
    }

    /** Validates a package without installing it (for the confirmation screen). */
    fun inspect(file: File): ModArchive.Inspection = ModArchive.inspect(file)

    /**
     * Installs (or upgrades) a package. New mods start enabled; upgrades keep
     * the previous enabled state.
     */
    fun install(file: File, nowMs: Long = System.currentTimeMillis()): InstalledMod {
        val inspection = ModArchive.inspect(file)
        val manifest = inspection.manifest
        if (!manifest.supportsThisAdvanceX()) {
            throw ModException("\"${manifest.name}\" requires AdvanceX ${manifest.advanceXVersion}.")
        }
        val staging = File(root, ".staging-${manifest.id}-$nowMs")
        try {
            ModArchive.extract(file, staging)
            val target = File(root, manifest.id)
            val trash = File(root, ".trash-${manifest.id}-$nowMs")
            if (target.exists() && !target.renameTo(trash)) throw ModException("Could not replace the installed version.")
            if (!staging.renameTo(target)) {
                trash.renameTo(target) // roll back
                throw ModException("Could not install the mod (storage error).")
            }
            trash.deleteRecursively()
            val registry = readRegistry()
            val previous = registry.mods.firstOrNull { it.id == manifest.id }
            val entry = RegistryEntry(manifest.id, previous?.enabled ?: true, nowMs)
            writeRegistry(Registry(registry.mods.filterNot { it.id == manifest.id } + entry))
            Log.i(TAG, "Installed mod ${manifest.id} ${manifest.version}")
            return InstalledMod(manifest, inspection.contents, entry.enabled, nowMs, target)
        } finally {
            staging.deleteRecursively()
        }
    }

    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val registry = readRegistry()
        if (registry.mods.none { it.id == id }) return false
        writeRegistry(Registry(registry.mods.map { if (it.id == id) it.copy(enabled = enabled) else it }))
        return true
    }

    fun remove(id: String): Boolean {
        val registry = readRegistry()
        if (registry.mods.none { it.id == id }) return false
        writeRegistry(Registry(registry.mods.filterNot { it.id == id }))
        File(root, id).deleteRecursively()
        return true
    }

    fun compatibility(mod: InstalledMod, identity: RomIdentity?): Compatibility = when {
        !mod.manifest.supportsThisAdvanceX() -> Compatibility.NEEDS_NEWER_APP
        mod.manifest.isUniversal -> Compatibility.UNIVERSAL
        identity != null && mod.manifest.isCompatibleWith(identity) -> Compatibility.COMPATIBLE
        else -> Compatibility.WRONG_GAME
    }

    /** Content roots of enabled mods usable with [identity] (for the profile repository). */
    fun contentRoots(identity: RomIdentity?): List<ContentRoot> =
        installed().filter { it.enabled }
            .filter { compatibility(it, identity) in setOf(Compatibility.COMPATIBLE, Compatibility.UNIVERSAL) }
            .map { DirectoryContentRoot(it.directory, ContentOrigin.Mod(it.id)) }

    private fun countContents(dir: File): ModContents {
        fun count(sub: String, filter: (File) -> Boolean = { true }) =
            File(dir, sub).walkTopDown().count { it.isFile && filter(it) }
        return ModContents(
            profiles = count("profiles") { it.name == "profile.json" },
            patches = count("patches"),
            textures = count("textures"),
            sprites = count("sprites"),
            audio = count("audio"),
            shaders = count("shaders"),
            scripts = count("scripts"),
            totalBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
        )
    }

    private fun readRegistry(): Registry =
        runCatching { AdvanceJson.decodeFromString(Registry.serializer(), registryFile.readText()) }.getOrElse { Registry() }

    private fun writeRegistry(registry: Registry) {
        val tmp = File(registryFile.path + ".tmp")
        FileOutputStream(tmp).use {
            it.write(AdvanceJson.encodeToString(Registry.serializer(), registry).toByteArray())
            it.fd.sync()
        }
        tmp.renameTo(registryFile)
    }

    private fun cleanupStaging() {
        root.listFiles()?.filter { it.name.startsWith(".staging-") || it.name.startsWith(".trash-") }
            ?.forEach { it.deleteRecursively() }
    }

    companion object {
        private const val TAG = "Mods"
    }
}

/** Builds `.advx` packages from a folder (used by Advance Studio's "Export .advx"). */
object ModPackager {
    /** Packs [sourceDir] (must contain manifest.json) into [output]. Returns the parsed manifest. */
    fun pack(sourceDir: File, output: File): ModManifest {
        val manifest = ModManifest.parse(File(sourceDir, ModArchive.MANIFEST).takeIf { it.isFile }?.readText()
            ?: throw ModException("The folder has no manifest.json."))
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            sourceDir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(sourceDir).invariantSeparatorsPath }
                .forEach { file ->
                    val rel = file.relativeTo(sourceDir).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(rel))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
        ModArchive.inspect(output) // fail early if the result would be rejected
        return manifest
    }
}
