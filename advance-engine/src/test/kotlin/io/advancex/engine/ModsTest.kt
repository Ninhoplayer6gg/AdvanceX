// SPDX-License-Identifier: MPL-2.0
package io.advancex.engine

import io.advancex.engine.TestFixtures.bundledRoot
import io.advancex.engine.TestFixtures.otherRevisionIdentity
import io.advancex.engine.TestFixtures.testCartIdentity
import io.advancex.engine.content.ContentOrigin
import io.advancex.engine.graphics.ShaderPresets
import io.advancex.engine.mods.Compatibility
import io.advancex.engine.mods.ModException
import io.advancex.engine.mods.ModManager
import io.advancex.engine.mods.ModManifest
import io.advancex.engine.mods.ModPackager
import io.advancex.engine.mods.SemVer
import io.advancex.engine.mods.VersionConstraint
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModsTest {
    private fun zip(file: File, entries: Map<String, String>) {
        ZipOutputStream(FileOutputStream(file)).use { z ->
            for ((name, content) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray())
                z.closeEntry()
            }
        }
    }

    private val minimalManifest = """{ "name": "Tiny", "version": "1.0.0", "author": "me", "advanceXVersion": ">=0.1.0" }"""

    @Test
    fun demoPackInstallsAndOverridesBundledProfile() {
        val work = TestFixtures.tempDir("mods")
        val advx = File(work, "demo.advx")
        val manifest = ModPackager.pack(TestFixtures.demoModSource, advx)
        assertEquals("advancex-demo-pack", manifest.id)

        val manager = ModManager(File(work, "installed"))
        val inspection = manager.inspect(advx)
        assertEquals(1, inspection.contents.profiles)
        assertEquals(1, inspection.contents.patches)
        assertEquals(1, inspection.contents.sprites)
        assertFalse(inspection.contents.containsScripts)
        assertTrue(inspection.ignored.isEmpty()) // README.md is allowed

        val installed = manager.install(advx)
        assertTrue(installed.enabled)
        assertEquals(Compatibility.COMPATIBLE, manager.compatibility(installed, testCartIdentity))
        assertEquals(Compatibility.WRONG_GAME, manager.compatibility(installed, otherRevisionIdentity))
        assertEquals("1.0.0", manager.installed().single().manifest.version.toString())

        val engine = AdvanceEngine { identity -> listOf(bundledRoot) + manager.contentRoots(identity) }
        val plan = engine.plan(EngineRequest(testCartIdentity, advanceMode = true))
        assertEquals(ContentOrigin.Mod("advancex-demo-pack"), plan.match!!.loaded.origin)
        assertEquals(ShaderPresets.SCANLINES.video, plan.videoHints)
        assertEquals(listOf("max-score"), plan.patches.map { it.id })
        assertEquals("sprites/star_hd.png", plan.replacements.single().path)

        // Disabling the mod falls back to the bundled profile.
        manager.setEnabled(installed.id, false)
        val bundledPlan = engine.plan(EngineRequest(testCartIdentity, advanceMode = true))
        assertEquals(ContentOrigin.Bundled, bundledPlan.match!!.loaded.origin)

        // Re-installing keeps the user's enabled choice; removing deletes files.
        manager.install(advx)
        assertFalse(manager.installed().single().enabled)
        assertTrue(manager.remove(installed.id))
        assertTrue(manager.installed().isEmpty())
        assertFalse(File(work, "installed/advancex-demo-pack").exists())
    }

    @Test
    fun rejectsUnsafeOrInvalidPackages() {
        val work = TestFixtures.tempDir("bad")
        val manager = ModManager(File(work, "installed"))

        val traversal = File(work, "traversal.advx")
        zip(traversal, mapOf("manifest.json" to minimalManifest, "../evil.txt" to "x"))
        assertFailsWith<ModException> { manager.install(traversal) }.also { assertTrue("unsafe path" in it.message!!) }

        val noManifest = File(work, "nomanifest.advx")
        zip(noManifest, mapOf("profiles/a/profile.json" to "{}"))
        assertFailsWith<ModException> { manager.install(noManifest) }

        val future = File(work, "future.advx")
        zip(future, mapOf("manifest.json" to minimalManifest.replace(">=0.1.0", ">=9.0.0")))
        assertFailsWith<ModException> { manager.install(future) }.also { assertTrue("requires AdvanceX" in it.message!!) }

        val notZip = File(work, "plain.advx").apply { writeText("hello") }
        assertFailsWith<ModException> { manager.install(notZip) }

        // Unknown top-level folders are ignored, scripts are flagged (never run).
        val extra = File(work, "extra.advx")
        zip(extra, mapOf("manifest.json" to minimalManifest, "bin/tool.so" to "x", "scripts/main.lua" to "print()"))
        val inspection = manager.inspect(extra)
        assertEquals(listOf("bin/tool.so"), inspection.ignored)
        assertTrue(inspection.contents.containsScripts)
        val mod = manager.install(extra)
        assertFalse(File(mod.directory, "bin/tool.so").exists())
        assertEquals(Compatibility.UNIVERSAL, manager.compatibility(mod, testCartIdentity))
        assertTrue(manager.installed().isNotEmpty())
        assertTrue(File(work, "installed").listFiles()!!.none { it.name.startsWith(".staging") })
    }

    @Test
    fun manifestParsingAndCompatibleGameForms() {
        val m = ModManifest.parse(
            """{ "name": "My Pack!", "version": "2.1", "compatibleGames": ["ZAXT", "45de1f88",
                 { "gameCode": "AAAA" }, "4edb7d353e87c788367ec5b38c67251312398230" ] }""",
        )
        assertEquals("my-pack", m.id)
        assertEquals(SemVer(2, 1, 0), m.version)
        assertEquals(4, m.compatibleGames.size)
        assertTrue(m.isCompatibleWith(testCartIdentity))
        assertTrue(m.supportsThisAdvanceX())
        assertFailsWith<ModException> { ModManifest.parse("""{ "version": "1.0.0" }""") }
        assertFailsWith<ModException> { ModManifest.parse("""{ "name": "x", "version": "one" }""") }
        assertFailsWith<ModException> { ModManifest.parse("""{ "name": "x", "version": "1.0.0", "compatibleGames": ["??"] }""") }
        assertFailsWith<ModException> { ModManifest.parse("not json") }
    }

    @Test
    fun semverConstraints() {
        val v010 = SemVer.parse("0.1.0")!!
        assertTrue(VersionConstraint.parse(">=0.1.0")!!.isSatisfiedBy(v010))
        assertFalse(VersionConstraint.parse(">0.1.0")!!.isSatisfiedBy(v010))
        assertTrue(VersionConstraint.parse(">=0.1.0 <1.0.0")!!.isSatisfiedBy(SemVer.parse("0.9.9")!!))
        assertFalse(VersionConstraint.parse(">=0.1.0, <1.0.0")!!.isSatisfiedBy(SemVer.parse("1.0.0")!!))
        assertTrue(VersionConstraint.parse("^0.1.2")!!.isSatisfiedBy(SemVer.parse("0.1.9")!!))
        assertFalse(VersionConstraint.parse("^0.1.2")!!.isSatisfiedBy(SemVer.parse("0.2.0")!!))
        assertTrue(VersionConstraint.parse("^1.2")!!.isSatisfiedBy(SemVer.parse("1.9.0")!!))
        assertTrue(VersionConstraint.parse("~1.2.3")!!.isSatisfiedBy(SemVer.parse("1.2.8")!!))
        assertFalse(VersionConstraint.parse("~1.2.3")!!.isSatisfiedBy(SemVer.parse("1.3.0")!!))
        assertTrue(VersionConstraint.parse("0.1.x")!!.isSatisfiedBy(SemVer.parse("0.1.7")!!))
        assertTrue(VersionConstraint.parse("*")!!.isSatisfiedBy(v010))
        assertTrue(SemVer.parse("1.0.0-beta")!! < SemVer.parse("1.0.0")!!)
        assertEquals(null, VersionConstraint.parse(">=banana"))
        assertNotNull(SemVer.parse("v1"))
    }
}
