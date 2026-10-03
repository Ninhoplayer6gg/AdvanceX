// SPDX-License-Identifier: MPL-2.0
package io.advancex.core.saves

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaveManagerTest {
    private val root: File = Files.createTempDirectory("axsaves").toFile()
    private val layout = SaveLayout(root)
    private val manager = SaveManager(layout, maxCartridgeBackups = 3)
    private val game = "ZAXT_4edb7d35"

    @Test
    fun layoutMatchesDocumentedStructure() {
        assertTrue(layout.ensureDirs(game))
        assertEquals(File(root, "$game/cartridge/game.sav"), layout.cartridgeSave(game))
        assertEquals(File(root, "$game/states/slot3.axstate"), layout.stateFile(game, StateSlot(3)))
        assertEquals(File(root, "$game/states/auto.axstate"), layout.stateFile(game, StateSlot.AUTO))
        assertEquals(File(root, "$game/screenshots/slot1.png"), layout.screenshotFile(game, StateSlot(1)))
        assertEquals("a_b_c", SaveLayout.sanitize("a/b\\c"))
        assertEquals("_____etc_passwd", SaveLayout.sanitize("../../etc/passwd").takeLast(15))
        assertFailsWith<IllegalArgumentException> { StateSlot(10) }
    }

    @Test
    fun atomicWriteKeepsPreviousGeneration() {
        val f = File(root, "x/data.bin")
        AtomicFiles.write(f, byteArrayOf(1, 2, 3), keepBackup = true)
        AtomicFiles.write(f, byteArrayOf(4, 5), keepBackup = true)
        assertContentEquals(byteArrayOf(4, 5), f.readBytes())
        assertContentEquals(byteArrayOf(1, 2, 3), File(f.path + ".bak").readBytes())
        assertFalse(File(f.path + ".tmp").exists())
        // An emptied primary falls back to the backup.
        f.writeBytes(ByteArray(0))
        val read = assertNotNull(AtomicFiles.readWithBackup(f))
        assertTrue(read.usedBackup)
        assertContentEquals(byteArrayOf(1, 2, 3), read.data)
    }

    @Test
    fun cartridgeBackupsRotateAndSkipDuplicates() {
        layout.ensureDirs(game)
        val save = layout.cartridgeSave(game)
        assertNull(manager.snapshotCartridge(game))
        for (i in 1..5) {
            save.writeBytes(byteArrayOf(i.toByte()))
            manager.snapshotCartridge(game, nowMs = 1_000_000L * i)
        }
        // Unchanged save: no new backup.
        manager.snapshotCartridge(game, nowMs = 9_000_000L)
        val backups = manager.cartridgeBackups(game)
        assertEquals(3, backups.size)
        assertContentEquals(byteArrayOf(5), backups.first().file.readBytes())

        // Restoring keeps the current save as a backup first.
        save.writeBytes(byteArrayOf(42))
        manager.restoreCartridgeBackup(game, backups.last())
        assertContentEquals(byteArrayOf(3), save.readBytes())
        assertContentEquals(byteArrayOf(42), manager.cartridgeBackups(game).first().file.readBytes())
    }

    @Test
    fun parsesStateHeaderAndListsSlots() {
        layout.ensureDirs(game)
        val payload = ByteArray(10) { it.toByte() }
        val header = ByteBuffer.allocate(StateFileHeader.SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("AXST".toByteArray())
            putInt(1)
            putInt(StateFileHeader.SIZE)
            putInt(1)
            put("mgba".toByteArray().copyOf(16))
            put("0.10.5".toByteArray().copyOf(16))
            putInt(0x45de1f88.toInt())
            putInt(6156)
            putLong(1_700_000_000_000L)
            putInt(1234)
            putInt(400_000)
            putInt(payload.size)
            putInt(0)
        }.array()
        layout.stateFile(game, StateSlot(2)).writeBytes(header + payload)
        layout.stateFile(game, StateSlot(3)).writeBytes(header) // truncated: invalid

        val slots = manager.slots(game)
        assertEquals(10, slots.size)
        val s2 = slots.first { it.slot.index == 2 }
        val h = assertNotNull(s2.header)
        assertEquals("mgba", h.coreId)
        assertEquals("0.10.5", h.coreVersion)
        assertEquals(1234L, h.frameCounter)
        assertTrue(h.belongsTo("45DE1F88"))
        assertFalse(slots.first { it.slot.index == 3 }.exists)

        manager.deleteState(game, StateSlot(2))
        assertFalse(manager.slots(game).first { it.slot.index == 2 }.exists)
    }

    @Test
    fun exportsAllSaveFilesToZip() {
        layout.ensureDirs(game)
        layout.cartridgeSave(game).writeBytes(byteArrayOf(7))
        layout.stateFile(game, StateSlot(1)).writeBytes(byteArrayOf(8))
        val out = ByteArrayOutputStream()
        manager.exportZip(game, out)
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) names += (zip.nextEntry ?: break).name
        }
        assertTrue("$game/cartridge/game.sav" in names)
        assertTrue("$game/states/slot1.axstate" in names)
    }

    @Test
    fun importRejectsImplausibleSaves() {
        assertFailsWith<IllegalArgumentException> { manager.importCartridgeSave(game, ByteArray(0)) }
        manager.importCartridgeSave(game, ByteArray(32768) { 1 })
        assertEquals(32768L, layout.cartridgeSave(game).length())
    }
}
