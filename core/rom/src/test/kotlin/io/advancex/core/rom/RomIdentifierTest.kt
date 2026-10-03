// SPDX-License-Identifier: MPL-2.0
package io.advancex.core.rom

import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RomIdentifierTest {
    private val testCart: File =
        File(System.getProperty("advancex.repoRoot", "../.."), "tools/testrom/dist/advancex-testcart.gba")

    @Test
    fun identifiesTestCartridge() {
        val result = RomIdentifier.identify(testCart)
        assertIs<RomCheck.Ok>(result)
        val id = result.identity
        assertEquals("ADVANCEXTEST", id.header.title)
        assertEquals("ZAXT", id.header.gameCode)
        assertEquals("AX", id.header.makerCode)
        assertTrue(id.header.checksumValid)
        assertTrue(id.header.fixedValueValid)
        assertEquals(6156L, id.hashes.size)
        assertEquals("45de1f88", id.hashes.crc32)
        assertEquals("4edb7d353e87c788367ec5b38c67251312398230", id.hashes.sha1)
        assertEquals("d2f55dd40cea140b277615f0ce679a7f170fa25861646fd0e371f50af125c745", id.hashes.sha256)
        assertEquals("ZAXT_4edb7d35", id.gameId)
        assertEquals(GbaRegion.UNKNOWN, id.header.region)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun hashMatchingDetectsAlgorithmByLength() {
        val id = (RomIdentifier.identify(testCart) as RomCheck.Ok).identity
        assertTrue(id.hashes.matches("45DE1F88"))
        assertTrue(id.hashes.matches("4edb7d353e87c788367ec5b38c67251312398230"))
        assertTrue(id.hashes.matches("d2f55dd4 0cea140b 277615f0 ce679a7f 170fa258 61646fd0 e371f50a f125c745"))
        assertFalse(id.hashes.matches("00000000"))
        assertFalse(id.hashes.matches("abc"))
    }

    @Test
    fun rejectsSmallAndNonGbaFiles() {
        assertEquals(
            RomCheck.Rejected(RomRejection.TOO_SMALL),
            RomIdentifier.identify(ByteArrayInputStream(ByteArray(100))),
        )
        // 1 KiB of zeros: big enough, but no ARM branch at the entry point.
        assertEquals(
            RomCheck.Rejected(RomRejection.NOT_A_ROM),
            RomIdentifier.identify(ByteArrayInputStream(ByteArray(1024))),
        )
    }

    @Test
    fun warnsAboutDamagedHeader() {
        val bytes = testCart.readBytes()
        bytes[0xBD] = (bytes[0xBD] + 1).toByte()
        val result = RomIdentifier.identify(ByteArrayInputStream(bytes))
        assertIs<RomCheck.Ok>(result)
        assertEquals(listOf(RomWarning.BAD_HEADER_CHECKSUM), result.warnings)
    }

    @Test
    fun regionAndGameIdForCommercialStyleCodes() {
        assertEquals(GbaRegion.USA, GbaRegion.fromGameCode("AXVE"))
        assertEquals(GbaRegion.EUROPE, GbaRegion.fromGameCode("BPEP"))
        assertEquals(GbaRegion.JAPAN, GbaRegion.fromGameCode("AGBJ"))
        assertEquals(GbaRegion.UNKNOWN, GbaRegion.fromGameCode(""))
        val header = GbaHeader("", "", "", 0, 0, true, true, true)
        val id = RomIdentity(header, RomHashes("00000000", "a".repeat(40), "b".repeat(64), 4))
        assertEquals("HOMEBREW_aaaaaaaa", id.gameId)
        assertEquals("Unknown game", id.displayTitle)
    }
}
