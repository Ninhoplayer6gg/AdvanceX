// SPDX-License-Identifier: MPL-2.0
package io.advancex.engine.patches

/** A problem with one patch definition. */
data class PatchIssue(val patchId: String, val message: String)

data class PatchValidation(val patches: List<RuntimePatch>, val issues: List<PatchIssue>)

/**
 * Turns patch definitions into [RuntimePatch]es, rejecting anything unsafe.
 * The rules mirror the native PatchEngine so problems are reported to the
 * user before the game starts (the native side re-checks everything).
 */
object PatchValidator {
    private const val MAX_BYTES = 4096
    private const val ROM_HEADER_END = 0xC0L
    private val ID_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")

    fun validate(
        definitions: List<PatchDefinition>,
        romSize: Long,
        toggles: Map<String, Boolean> = emptyMap(),
    ): PatchValidation {
        val ok = mutableListOf<RuntimePatch>()
        val issues = mutableListOf<PatchIssue>()
        val seen = mutableSetOf<String>()
        for (d in definitions) {
            val problem = check(d, romSize, seen)
            if (problem != null) {
                issues += PatchIssue(d.id, problem)
                continue
            }
            seen += d.id
            val enabled = toggles[d.id] ?: d.enabledByDefault
            val bytes = parseHex(d.bytes)!!
            val expect = d.expect?.let { parseHex(it) }
            ok += if (d.type == "rom") {
                RuntimePatch.Rom(d.id, d.name.ifBlank { d.id }, d.description, parseNumber(d.offset!!)!!, bytes, expect, enabled)
            } else {
                val cond = d.condition?.let {
                    RuntimePatch.Condition(parseNumber(it.address)!!, it.width, RuntimePatch.CompareOp.parse(it.op)!!, it.value)
                }
                RuntimePatch.Memory(
                    d.id, d.name.ifBlank { d.id }, d.description, parseNumber(d.address!!)!!, bytes, expect,
                    once = d.mode == "once", condition = cond, enabled = enabled,
                )
            }
        }
        return PatchValidation(ok, issues)
    }

    private fun check(d: PatchDefinition, romSize: Long, seen: Set<String>): String? {
        if (!d.id.matches(ID_PATTERN)) return "Invalid patch id (use letters, digits, '.', '_' or '-')."
        if (d.id in seen) return "Duplicate patch id."
        val bytes = parseHex(d.bytes) ?: return "'bytes' is not valid hex."
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return "A patch must write 1 to $MAX_BYTES bytes."
        if (d.expect != null) {
            val expect = parseHex(d.expect) ?: return "'expect' is not valid hex."
            if (expect.size != bytes.size) return "'expect' must have the same length as 'bytes'."
        }
        return when (d.type) {
            "rom" -> {
                val offset = d.offset?.let { parseNumber(it) } ?: return "ROM patches need a valid 'offset'."
                when {
                    offset < ROM_HEADER_END -> "The cartridge header (0x00-0xBF) cannot be patched."
                    offset + bytes.size > romSize -> "Offset ${hex(offset)} is outside this ROM (${romSize} bytes)."
                    d.condition != null || d.mode != "everyFrame" -> "ROM patches are applied once at load; remove 'mode'/'condition'."
                    else -> null
                }
            }
            "memory" -> {
                val address = d.address?.let { parseNumber(it) } ?: return "Memory patches need a valid 'address'."
                val region = PatchRegion.of(address)
                    ?: return "Address ${hex(address)} is not in writable RAM (EWRAM, IWRAM, palette, VRAM or OAM)."
                if (address + bytes.size > region.base + region.size) return "The patch crosses the end of ${region.displayName}."
                if (d.mode != "everyFrame" && d.mode != "once") return "'mode' must be \"everyFrame\" or \"once\"."
                d.condition?.let { c ->
                    val ca = parseNumber(c.address) ?: return "Condition address is invalid."
                    if (!PatchRegion.isMapped(ca)) return "Condition address ${hex(ca)} is unmapped."
                    if (c.width !in setOf(1, 2, 4)) return "Condition width must be 1, 2 or 4."
                    if (RuntimePatch.CompareOp.parse(c.op) == null) return "Condition 'op' must be one of ==, !=, <, >."
                    if (c.value < 0 || c.value > 0xFFFFFFFFL) return "Condition value must fit in 32 bits."
                }
                null
            }
            else -> "'type' must be \"memory\" or \"rom\"."
        }
    }

    /** Parses "09 00 0a FF", "09000aff" or "0x09,0x00" style hex. */
    fun parseHex(text: String): ByteArray? {
        val clean = text.replace("0x", "", ignoreCase = true).filterNot { it.isWhitespace() || it == ',' || it == ':' }
        if (clean.length % 2 != 0) return null
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** Parses "0x02000000", "02000000h" or decimal. */
    fun parseNumber(text: String): Long? {
        val t = text.trim().replace("_", "")
        val value = when {
            t.startsWith("0x", ignoreCase = true) -> t.substring(2).toLongOrNull(16)
            t.endsWith("h", ignoreCase = true) -> t.dropLast(1).toLongOrNull(16)
            else -> t.toLongOrNull()
        }
        return value?.takeIf { it in 0..0xFFFFFFFFL }
    }

    private fun hex(v: Long) = "0x%08X".format(v)
}
