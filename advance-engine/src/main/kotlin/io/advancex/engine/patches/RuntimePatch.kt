// SPDX-License-Identifier: MPL-2.0
package io.advancex.engine.patches

/** Parsed, validated patches ready for the native engine. */
sealed class RuntimePatch {
    abstract val id: String
    abstract val name: String
    abstract val description: String
    abstract val bytes: ByteArray
    abstract val expect: ByteArray?
    abstract val enabled: Boolean

    data class Rom(
        override val id: String,
        override val name: String,
        override val description: String,
        val offset: Long,
        override val bytes: ByteArray,
        override val expect: ByteArray?,
        override val enabled: Boolean,
    ) : RuntimePatch()

    data class Memory(
        override val id: String,
        override val name: String,
        override val description: String,
        val address: Long,
        override val bytes: ByteArray,
        override val expect: ByteArray?,
        val once: Boolean,
        val condition: Condition?,
        override val enabled: Boolean,
    ) : RuntimePatch()

    data class Condition(val address: Long, val width: Int, val op: CompareOp, val value: Long)

    /** Matches the native ax::engine::CompareOp numbering. */
    enum class CompareOp(val nativeId: Int, val symbol: String) {
        EQUAL(1, "=="),
        NOT_EQUAL(2, "!="),
        LESS(3, "<"),
        GREATER(4, ">");

        companion object {
            fun parse(symbol: String): CompareOp? = entries.firstOrNull { it.symbol == symbol.trim() }
        }
    }
}

/** GBA memory regions a memory patch may write (mirrors the native validator). */
enum class PatchRegion(val base: Long, val size: Long, val displayName: String) {
    EWRAM(0x02000000, 0x40000, "EWRAM"),
    IWRAM(0x03000000, 0x8000, "IWRAM"),
    PALETTE(0x05000000, 0x400, "Palette RAM"),
    VRAM(0x06000000, 0x18000, "VRAM"),
    OAM(0x07000000, 0x400, "OAM");

    companion object {
        fun of(address: Long): PatchRegion? = entries.firstOrNull { address >= it.base && address < it.base + it.size }

        /** Any mapped GBA address (readable for conditions). */
        fun isMapped(address: Long): Boolean =
            of(address) != null ||
                address in 0x00000000L until 0x00004000L ||
                address in 0x04000000L until 0x04000400L ||
                address in 0x08000000L until 0x0A000000L ||
                address in 0x0E000000L until 0x0E010000L
    }
}
