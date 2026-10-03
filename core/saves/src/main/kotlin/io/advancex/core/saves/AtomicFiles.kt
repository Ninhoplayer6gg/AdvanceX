// SPDX-License-Identifier: MPL-2.0
package io.advancex.core.saves

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Crash-safe file helpers (Kotlin counterpart of native `ax::fileio`).
 *
 * A write goes to `<name>.tmp`, is fsync'ed, then renamed over the target.
 * With `keepBackup`, the previous contents are first copied to `<name>.bak`.
 * At every instant either the old or the new complete file exists.
 */
object AtomicFiles {
    @Throws(IOException::class)
    fun write(target: File, data: ByteArray, keepBackup: Boolean = false) {
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(data)
            out.flush()
            out.fd.sync()
        }
        if (keepBackup && target.isFile && target.length() > 0) {
            val bakTmp = File(target.path + ".bak.tmp")
            FileOutputStream(bakTmp).use { out ->
                target.inputStream().use { it.copyTo(out) }
                out.flush()
                out.fd.sync()
            }
            if (!bakTmp.renameTo(File(target.path + ".bak"))) {
                bakTmp.delete()
            }
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Cannot replace ${target.path}")
        }
    }

    fun writeText(target: File, text: String, keepBackup: Boolean = false) =
        write(target, text.toByteArray(Charsets.UTF_8), keepBackup)

    /** Reads [file], falling back to its `.bak` copy if the primary is missing or empty. */
    fun readWithBackup(file: File): ReadResult? {
        if (file.isFile && file.length() > 0) {
            runCatching { return ReadResult(file.readBytes(), usedBackup = false) }
        }
        val bak = File(file.path + ".bak")
        if (bak.isFile && bak.length() > 0) {
            runCatching { return ReadResult(bak.readBytes(), usedBackup = true) }
        }
        return null
    }

    class ReadResult(val data: ByteArray, val usedBackup: Boolean)
}
