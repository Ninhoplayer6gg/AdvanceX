// SPDX-License-Identifier: MPL-2.0
package io.advancex.engine

import io.advancex.engine.TestFixtures.bundledRoot
import io.advancex.engine.TestFixtures.otherRevisionIdentity
import io.advancex.engine.TestFixtures.testCartIdentity
import io.advancex.engine.content.ContentOrigin
import io.advancex.engine.content.ContentPaths
import io.advancex.engine.content.DirectoryContentRoot
import io.advancex.engine.graphics.ShaderPresets
import io.advancex.engine.patches.PatchBlobEncoder
import io.advancex.engine.patches.PatchDefinition
import io.advancex.engine.patches.PatchValidator
import io.advancex.engine.patches.RuntimePatch
import io.advancex.engine.profiles.MatchConfidence
import io.advancex.engine.profiles.ProfileRepository
import io.advancex.engine.replacements.ReplacementPacks
import io.advancex.engine.widescreen.WidescreenMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfilesAndPatchesTest {
    @Test
    fun bundledProfileMatchesTestCartExactly() {
        val repo = ProfileRepository(listOf(bundledRoot))
        assertTrue(repo.errors.isEmpty(), repo.errors.toString())
        val match = assertNotNull(repo.match(testCartIdentity))
        assertEquals("advancex_testcart", match.profile.id)
        assertEquals(MatchConfidence.EXACT, match.confidence)
        // Hash-pinned profiles never apply to another image with the same code.
        assertNull(repo.match(otherRevisionIdentity))
    }

    @Test
    fun gameCodeProfilesMatchLooselyButNeverEnablePatches() {
        val dir = TestFixtures.tempDir("profiles")
        File(dir, "profiles/generic").mkdirs()
        File(dir, "profiles/generic/profile.json").writeText(
            """{ "name": "Generic", "gameCode": "ZAXT", "enhancements": { "runtimePatches": true, "shader": true },
                 "graphics": { "preset": "crisp" }, "patches": ["p.json"] }""",
        )
        File(dir, "profiles/generic/p.json").writeText(
            """{ "patches": [ { "id": "x", "type": "memory", "address": "0x02000000", "bytes": "01" } ] }""",
        )
        File(dir, "profiles/broken").mkdirs()
        File(dir, "profiles/broken/profile.json").writeText("""{ "name": "No match rules" }""")
        val root = DirectoryContentRoot(dir, ContentOrigin.User)
        val repo = ProfileRepository(listOf(root))
        assertEquals(1, repo.errors.size)
        val match = assertNotNull(repo.match(otherRevisionIdentity))
        assertEquals(MatchConfidence.GAME_CODE, match.confidence)

        val plan = AdvanceEngine { listOf(root) }.plan(EngineRequest(otherRevisionIdentity, advanceMode = true))
        assertTrue(plan.patches.isEmpty())
        assertEquals(0, plan.patchBlob.size)
        assertTrue(plan.messages.any { it.warning && "exact ROM match" in it.text })
        assertEquals(ShaderPresets.CRISP.video, plan.videoHints)
    }

    @Test
    fun originalModePlanIsInformationalOnly() {
        val plan = AdvanceEngine { listOf(bundledRoot) }.plan(EngineRequest(testCartIdentity, advanceMode = false))
        assertFalse(plan.advanceMode)
        assertNotNull(plan.match)
        assertTrue(plan.patches.isEmpty() && plan.replacements.isEmpty())
        assertNull(plan.videoHints)
        assertEquals(0, plan.patchBlob.size)
        assertTrue(plan.activeFeatures.isEmpty())
    }

    @Test
    fun advanceModePlanForTestCart() {
        val plan = AdvanceEngine { listOf(bundledRoot) }.plan(
            EngineRequest(testCartIdentity, advanceMode = true, patchToggles = mapOf("score-boost" to true)),
        )
        assertTrue(plan.advanceMode)
        assertTrue(plan.messages.none { it.warning }, plan.messages.toString())
        assertEquals(ShaderPresets.LCD.video, plan.videoHints)
        assertEquals(listOf("rom-patch-banner", "infinite-lives", "score-boost"), plan.patches.map { it.id })
        assertTrue(plan.patches.all { it.enabled })
        val boost = plan.patches[2] as RuntimePatch.Memory
        assertTrue(boost.once)
        assertEquals(RuntimePatch.CompareOp.GREATER, boost.condition?.op)
        assertEquals(2, plan.replacements.size)
        assertEquals("845471e4b21baf09", plan.replacements[0].keyHex)
        assertTrue(plan.patchBlob.isNotEmpty())
        assertEquals(listOf("patches", "replacements", "profile-graphics"), plan.activeFeatures)
    }

    @Test
    fun patchBlobMatchesNativeGoldenEncoding() {
        val patches = listOf(
            RuntimePatch.Rom("rom", "rom", "", 0x40B, "ON!".toByteArray(), "OFF".toByteArray(), true),
            RuntimePatch.Memory(
                "lives", "lives", "", 0x02000000, byteArrayOf(9, 0, 0, 0), null, once = false,
                condition = RuntimePatch.Condition(0x02000008, 4, RuntimePatch.CompareOp.GREATER, 100), enabled = true,
            ),
        )
        // Same bytes as native/tests/test_patch_blob.cpp (cross-language contract).
        val golden = "41585042" + "01000000" + "01000000" + "01000000" +
            "0300" + "726f6d" + "0b040000" +
            "03000000" + "4f4e21" + "03000000" + "4f4646" +
            "0500" + "6c69766573" + "00000002" +
            "04000000" + "09000000" + "00000000" +
            "00" + "04" + "04" + "08000002" + "64000000" + "01"
        assertEquals(golden, PatchBlobEncoder.encode(patches).joinToString("") { "%02x".format(it) })
        assertEquals(0, PatchBlobEncoder.encode(emptyList()).size)
    }

    @Test
    fun patchValidatorRejectsUnsafeDefinitions() {
        val defs = listOf(
            PatchDefinition(id = "io", address = "0x04000000", bytes = "00"),
            PatchDefinition(id = "header", type = "rom", offset = "0x10", bytes = "00"),
            PatchDefinition(id = "outside", type = "rom", offset = "0x2000", bytes = "00"),
            PatchDefinition(id = "badhex", address = "0x02000000", bytes = "0G"),
            PatchDefinition(id = "len", address = "0x02000000", bytes = "0102", expect = "01"),
            PatchDefinition(id = "ok", address = "0x0203FFFF", bytes = "01"),
            PatchDefinition(id = "ok", address = "0x02000000", bytes = "01"),
            PatchDefinition(id = "cross", address = "0x0203FFFF", bytes = "0102"),
            PatchDefinition(id = "type", type = "bios", address = "0x02000000", bytes = "01"),
            PatchDefinition(id = "bad id!", address = "0x02000000", bytes = "01"),
            PatchDefinition(
                id = "cond", address = "0x02000000", bytes = "01",
                condition = io.advancex.engine.patches.PatchConditionDefinition("0x02000000", width = 3),
            ),
        )
        val result = PatchValidator.validate(defs, romSize = 6156)
        assertEquals(listOf("ok"), result.patches.map { it.id })
        assertEquals(10, result.issues.size)
        assertEquals("ok", result.issues.first { it.message.contains("Duplicate") }.patchId)
        assertEquals(listOf<Byte>(9, 0, 0x0A, -1), PatchValidator.parseHex("09 00 0a FF")!!.toList())
        assertEquals(0x02000000L, PatchValidator.parseNumber("0x0200_0000"))
        assertNull(PatchValidator.parseNumber("0x1FFFFFFFF"))
    }

    @Test
    fun replacementPacksResolveFilesAndReportProblems() {
        val ok = ReplacementPacks.load(bundledRoot, "profiles/advancex_testcart/replacements/sprites.json")
        assertTrue(ok.problems.isEmpty())
        assertEquals(2, ok.entries.size)
        assertEquals("profiles/advancex_testcart/replacements/crystal_gold_hd.png", ok.entries[1].path)

        val dir = TestFixtures.tempDir("repl")
        File(dir, "r.json").writeText(
            """{ "kind": "sprite", "entries": [ { "key": "zz", "file": "a.png" },
                 { "key": "0000000000000001", "file": "missing.png" },
                 { "key": "0000000000000002", "file": "../../escape.png" } ] }""",
        )
        val bad = ReplacementPacks.load(DirectoryContentRoot(dir, ContentOrigin.User), "r.json")
        assertTrue(bad.entries.isEmpty())
        assertEquals(3, bad.problems.size)
        File(dir, "t.json").writeText("""{ "kind": "tile", "entries": [] }""")
        assertTrue(ReplacementPacks.load(DirectoryContentRoot(dir, ContentOrigin.User), "t.json").problems.single().contains("coming later"))
    }

    @Test
    fun contentPathsBlockTraversal() {
        assertEquals("a/c", ContentPaths.normalize("a/./b/../c"))
        assertNull(ContentPaths.normalize("../x"))
        assertNull(ContentPaths.normalize("/etc/passwd"))
        assertNull(ContentPaths.normalize("C:\\x"))
        assertEquals("patches/s.json", ContentPaths.sibling("profiles/p/profile.json", "../../patches/s.json"))
        assertNull(ContentPaths.sibling("profiles/p/profile.json", "../../../x"))
    }

    @Test
    fun widescreenNeverStretchesAndNativeFallsBack() {
        val match = ProfileRepository(listOf(bundledRoot)).match(testCartIdentity)
        val io = io.advancex.engine.widescreen.WidescreenResolver
        assertEquals(WidescreenMode.OFF, io.resolve(WidescreenMode.DISPLAY, advanceMode = false, match = match).mode)
        assertEquals(WidescreenMode.DISPLAY, io.resolve(WidescreenMode.DISPLAY, true, match).mode)
        val native = io.resolve(WidescreenMode.NATIVE, true, match)
        assertEquals(WidescreenMode.DISPLAY, native.mode)
        assertTrue(native.reason!!.contains("no native widescreen"))
        assertEquals(ShaderPresets.all.size, ShaderPresets.all.map { it.id }.toSet().size)
    }
}
