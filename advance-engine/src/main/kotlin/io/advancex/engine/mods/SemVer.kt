// SPDX-License-Identifier: MPL-2.0
package io.advancex.engine.mods

/** Semantic version (major.minor.patch, optional -prerelease). */
data class SemVer(val major: Int, val minor: Int, val patch: Int, val preRelease: String? = null) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        // A pre-release sorts before the release.
        return when {
            preRelease == other.preRelease -> 0
            preRelease == null -> 1
            other.preRelease == null -> -1
            else -> preRelease.compareTo(other.preRelease)
        }
    }

    override fun toString(): String = "$major.$minor.$patch" + (preRelease?.let { "-$it" } ?: "")

    companion object {
        private val PATTERN = Regex("""^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?$""")

        fun parse(text: String): SemVer? {
            val m = PATTERN.matchEntire(text.trim()) ?: return null
            return SemVer(
                m.groupValues[1].toInt(),
                m.groupValues[2].ifEmpty { "0" }.toInt(),
                m.groupValues[3].ifEmpty { "0" }.toInt(),
                m.groupValues[4].ifEmpty { null },
            )
        }
    }
}

/**
 * Version constraint such as ">=0.1.0", ">=0.1.0 <1.0.0", "^0.2", "~0.1.2",
 * "0.1.x" or "*". Space- or comma-separated terms must all hold.
 */
class VersionConstraint private constructor(private val terms: List<(SemVer) -> Boolean>, val text: String) {
    fun isSatisfiedBy(version: SemVer): Boolean = terms.all { it(version) }

    override fun toString() = text

    companion object {
        fun parse(text: String): VersionConstraint? {
            val raw = text.trim()
            if (raw.isEmpty() || raw == "*") return VersionConstraint(emptyList(), raw.ifEmpty { "*" })
            val terms = mutableListOf<(SemVer) -> Boolean>()
            for (token in raw.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }) {
                terms += parseTerm(token) ?: return null
            }
            return VersionConstraint(terms, raw)
        }

        private fun parseTerm(token: String): ((SemVer) -> Boolean)? {
            val ops = listOf(">=", "<=", ">", "<", "==", "=", "^", "~")
            val op = ops.firstOrNull { token.startsWith(it) } ?: ""
            val versionText = token.removePrefix(op)
            if (op.isEmpty() && (versionText.endsWith(".x") || versionText.endsWith(".*"))) {
                val prefix = SemVer.parse(versionText.dropLast(2)) ?: return null
                val parts = versionText.count { it == '.' }
                return if (parts == 1) { v -> v.major == prefix.major }
                else { v -> v.major == prefix.major && v.minor == prefix.minor }
            }
            val v = SemVer.parse(versionText) ?: return null
            return when (op) {
                ">=" -> { x -> x >= v }
                "<=" -> { x -> x <= v }
                ">" -> { x -> x > v }
                "<" -> { x -> x < v }
                "", "=", "==" -> { x -> x.compareTo(v) == 0 }
                // ^1.2.3 := >=1.2.3 <2.0.0 ; ^0.2.3 := >=0.2.3 <0.3.0 (0.x is unstable)
                "^" -> if (v.major > 0) { x -> x >= v && x.major == v.major }
                else { x -> x >= v && x.major == 0 && x.minor == v.minor }
                // ~1.2.3 := >=1.2.3 <1.3.0
                "~" -> { x -> x >= v && x.major == v.major && x.minor == v.minor }
                else -> null
            }
        }
    }
}
