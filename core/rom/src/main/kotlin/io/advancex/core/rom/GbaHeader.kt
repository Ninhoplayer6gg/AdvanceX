// SPDX-License-Identifier: MPL-2.0
package io.advancex.core.rom

/**
 * Cartridge header fields (offsets 0xA0..0xBD of a GBA ROM image).
 *
 * Only publicly documented header fields are read. Nothing here depends on
 * the Nintendo logo area, which AdvanceX never needs or stores.
 */
data class GbaHeader(
    /** Internal title, up to 12 ASCII characters (often uppercase). */
    val title: String,
    /** 4-character game code, e.g. "AXVE". Empty for many homebrew images. */
    val gameCode: String,
    /** 2-character maker code. */
    val makerCode: String,
    /** Software revision byte (0 = first release, 1 = "Rev 1", ...). */
    val version: Int,
    /** Header complement checksum stored at 0xBD. */
    val checksum: Int,
    /** True if [checksum] matches the computed value. */
    val checksumValid: Boolean,
    /** True if the fixed byte at 0xB2 is 0x96, as on every licensed cartridge. */
    val fixedValueValid: Boolean,
    /** True if the first word is an ARM branch (how every GBA ROM starts). */
    val entryIsBranch: Boolean,
) {
    /** Region derived from the last character of the game code. */
    val region: GbaRegion get() = GbaRegion.fromGameCode(gameCode)

    companion object {
        const val HEADER_SIZE = 0xC0

        /** Parses the header from the first [HEADER_SIZE] bytes of a ROM. */
        fun parse(bytes: ByteArray): GbaHeader? {
            if (bytes.size < HEADER_SIZE) return null
            fun ascii(offset: Int, length: Int): String {
                val sb = StringBuilder()
                for (i in 0 until length) {
                    val c = bytes[offset + i].toInt() and 0xFF
                    if (c == 0) break
                    sb.append(if (c in 0x20..0x7E) c.toChar() else '?')
                }
                return sb.toString().trimEnd()
            }
            var sum = 0
            for (i in 0xA0..0xBC) sum -= bytes[i].toInt() and 0xFF
            val computed = (sum - 0x19) and 0xFF
            val stored = bytes[0xBD].toInt() and 0xFF
            return GbaHeader(
                title = ascii(0xA0, 12),
                gameCode = ascii(0xAC, 4),
                makerCode = ascii(0xB0, 2),
                version = bytes[0xBC].toInt() and 0xFF,
                checksum = stored,
                checksumValid = computed == stored,
                fixedValueValid = (bytes[0xB2].toInt() and 0xFF) == 0x96,
                // ARM "B" with condition AL: top byte 0xEA (little-endian, byte 3).
                entryIsBranch = (bytes[3].toInt() and 0xFF) == 0xEA,
            )
        }
    }
}

/** Distribution region encoded in the 4th character of the game code. */
enum class GbaRegion(val code: Char?, val displayName: String) {
    JAPAN('J', "Japan"),
    USA('E', "USA"),
    EUROPE('P', "Europe"),
    GERMANY('D', "Germany"),
    FRANCE('F', "France"),
    ITALY('I', "Italy"),
    SPAIN('S', "Spain"),
    UK('X', "United Kingdom"),
    NETHERLANDS('H', "Netherlands"),
    KOREA('K', "Korea"),
    CHINA('C', "China"),
    AUSTRALIA('U', "Australia"),
    UNKNOWN(null, "Unknown");

    companion object {
        fun fromGameCode(gameCode: String): GbaRegion {
            if (gameCode.length != 4) return UNKNOWN
            val c = gameCode[3]
            return entries.firstOrNull { it.code == c } ?: UNKNOWN
        }
    }
}
