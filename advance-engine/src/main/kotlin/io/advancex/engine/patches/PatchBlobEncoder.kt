// SPDX-License-Identifier: MPL-2.0
package io.advancex.engine.patches

import java.io.ByteArrayOutputStream

/**
 * Encodes validated patches into the AXPB blob decoded by the native runtime
 * (native/runtime/include/ax/runtime/patch_blob.h). Byte-for-byte contract
 * is covered by golden tests on both sides.
 */
object PatchBlobEncoder {
    private const val VERSION = 1

    fun encode(patches: List<RuntimePatch>): ByteArray {
        val rom = patches.filterIsInstance<RuntimePatch.Rom>().filter { it.enabled }
        // Memory patches keep their enabled flag so they can be toggled live.
        val memory = patches.filterIsInstance<RuntimePatch.Memory>()
        if (rom.isEmpty() && memory.isEmpty()) return ByteArray(0)
        val out = ByteArrayOutputStream()
        out.write("AXPB".toByteArray(Charsets.US_ASCII))
        out.u32(VERSION.toLong())
        out.u32(rom.size.toLong())
        out.u32(memory.size.toLong())
        for (p in rom) {
            out.str(p.id)
            out.u32(p.offset)
            out.bytes(p.bytes)
            out.bytes(p.expect ?: ByteArray(0))
        }
        for (p in memory) {
            out.str(p.id)
            out.u32(p.address)
            out.bytes(p.bytes)
            out.bytes(p.expect ?: ByteArray(0))
            out.write(if (p.once) 1 else 0)
            out.write(p.condition?.op?.nativeId ?: 0)
            out.write(p.condition?.width ?: 0)
            out.u32(p.condition?.address ?: 0)
            out.u32(p.condition?.value ?: 0)
            out.write(if (p.enabled) 1 else 0)
        }
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.u32(v: Long) {
        for (i in 0 until 4) write(((v ushr (8 * i)) and 0xFF).toInt())
    }

    private fun ByteArrayOutputStream.u16(v: Int) {
        write(v and 0xFF)
        write((v ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.str(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        u16(b.size)
        write(b)
    }

    private fun ByteArrayOutputStream.bytes(b: ByteArray) {
        u32(b.size.toLong())
        write(b)
    }
}
