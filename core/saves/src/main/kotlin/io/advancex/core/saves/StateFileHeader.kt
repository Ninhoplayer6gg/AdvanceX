// SPDX-License-Identifier: MPL-2.0
package io.advancex.core.saves

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Header of an `.axstate` file (format defined in native/runtime/state_file.h).
 * Reading it does not need the emulator, so the UI can list and validate
 * save states cheaply.
 */
data class StateFileHeader(
    val formatVersion: Int,
    val coreId: String,
    val coreVersion: String,
    val romCrc32: Long,
    val romSize: Long,
    val timestampMs: Long,
    val frameCounter: Long,
    val uncompressedSize: Long,
    val payloadSize: Long,
) {
    /** True if this state was created from the ROM with this CRC32 (8 hex digits). */
    fun belongsTo(romCrc32Hex: String): Boolean = "%08x".format(romCrc32) == romCrc32Hex.lowercase()

    companion object {
        const val SIZE = 96

        fun read(file: File): StateFileHeader? {
            if (!file.isFile || file.length() < SIZE) return null
            val bytes = ByteArray(SIZE)
            file.inputStream().use { input ->
                var off = 0
                while (off < SIZE) {
                    val n = input.read(bytes, off, SIZE - off)
                    if (n < 0) return null
                    off += n
                }
            }
            return parse(bytes, file.length())
        }

        fun parse(bytes: ByteArray, fileSize: Long): StateFileHeader? {
            if (bytes.size < SIZE) return null
            if (bytes[0] != 'A'.code.toByte() || bytes[1] != 'X'.code.toByte() ||
                bytes[2] != 'S'.code.toByte() || bytes[3] != 'T'.code.toByte()
            ) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun u32(offset: Int) = b.getInt(offset).toLong() and 0xFFFFFFFFL
            fun str(offset: Int): String {
                val end = (offset until offset + 16).firstOrNull { bytes[it].toInt() == 0 } ?: (offset + 16)
                return String(bytes, offset, end - offset, Charsets.US_ASCII)
            }
            val headerSize = u32(8)
            val payloadSize = u32(72)
            if (headerSize < SIZE || headerSize + payloadSize != fileSize) return null
            return StateFileHeader(
                formatVersion = u32(4).toInt(),
                coreId = str(16),
                coreVersion = str(32),
                romCrc32 = u32(48),
                romSize = u32(52),
                timestampMs = b.getLong(56),
                frameCounter = u32(64),
                uncompressedSize = u32(68),
                payloadSize = payloadSize,
            )
        }
    }
}
