// SPDX-License-Identifier: MPL-2.0
package io.advancex.core.rom

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32

/** Result of checking whether a file looks like a usable GBA ROM. */
sealed class RomCheck {
    data class Ok(val identity: RomIdentity, val warnings: List<RomWarning>) : RomCheck()
    data class Rejected(val reason: RomRejection) : RomCheck()
}

enum class RomRejection(val message: String) {
    TOO_SMALL("The file is too small to be a Game Boy Advance ROM."),
    TOO_LARGE("The file is larger than 32 MiB, the maximum size of a GBA cartridge."),
    NOT_A_ROM("This does not look like a Game Boy Advance ROM."),
}

enum class RomWarning(val message: String) {
    NONSTANDARD_HEADER("Non-standard header (common for homebrew and some hacks)."),
    BAD_HEADER_CHECKSUM("Header checksum mismatch: the ROM may be modified or damaged."),
    UNUSUAL_SIZE("Unusual file size for a GBA cartridge."),
}

/**
 * Identifies GBA ROM images by streaming them once and computing CRC32,
 * SHA-1 and SHA-256 together with the header. Memory use is constant
 * regardless of ROM size.
 */
object RomIdentifier {
    const val MAX_ROM_SIZE = 32L * 1024 * 1024

    fun identify(file: File): RomCheck = file.inputStream().buffered().use { identify(it) }

    fun identify(input: InputStream): RomCheck {
        val crc = CRC32()
        val sha1 = MessageDigest.getInstance("SHA-1")
        val sha256 = MessageDigest.getInstance("SHA-256")
        val headerBytes = ByteArray(GbaHeader.HEADER_SIZE)
        var headerFilled = 0
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (headerFilled < headerBytes.size) {
                val copy = minOf(n, headerBytes.size - headerFilled)
                System.arraycopy(buffer, 0, headerBytes, headerFilled, copy)
                headerFilled += copy
            }
            crc.update(buffer, 0, n)
            sha1.update(buffer, 0, n)
            sha256.update(buffer, 0, n)
            total += n
            if (total > MAX_ROM_SIZE) return RomCheck.Rejected(RomRejection.TOO_LARGE)
        }
        if (headerFilled < headerBytes.size) return RomCheck.Rejected(RomRejection.TOO_SMALL)
        val header = GbaHeader.parse(headerBytes) ?: return RomCheck.Rejected(RomRejection.TOO_SMALL)

        // Every GBA program starts with an ARM branch over the header. Without
        // it the file is almost certainly not a GBA ROM (e.g. a GB ROM or an
        // unrelated binary), so it is rejected outright.
        if (!header.entryIsBranch) return RomCheck.Rejected(RomRejection.NOT_A_ROM)

        val warnings = mutableListOf<RomWarning>()
        if (!header.fixedValueValid) warnings += RomWarning.NONSTANDARD_HEADER
        if (!header.checksumValid) warnings += RomWarning.BAD_HEADER_CHECKSUM
        if (total % 4 != 0L) warnings += RomWarning.UNUSUAL_SIZE

        val hashes = RomHashes(
            crc32 = "%08x".format(crc.value),
            sha1 = sha1.digest().toHex(),
            sha256 = sha256.digest().toHex(),
            size = total,
        )
        return RomCheck.Ok(RomIdentity(header, hashes), warnings)
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0xF])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
