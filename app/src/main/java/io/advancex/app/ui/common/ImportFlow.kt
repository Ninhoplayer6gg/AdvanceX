// SPDX-License-Identifier: MPL-2.0
package io.advancex.app.ui.common

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import io.advancex.app.R
import io.advancex.app.data.ImportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Import Game": opens the system file picker (no storage permission
 * needed), imports every selected file and reports the outcome.
 */
class ImportFlow(private val activity: BaseActivity, private val onImported: (List<ImportResult>) -> Unit) {

    fun start() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        @Suppress("DEPRECATION")
        activity.startActivityForResult(intent, REQUEST_CODE)
    }

    /** Call from Activity.onActivityResult. Returns true if handled. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_CODE) return false
        if (resultCode != Activity.RESULT_OK || data == null) return true
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri }
        if (uris.isEmpty()) data.data?.let { uris += it }
        if (uris.isNotEmpty()) importAll(uris)
        return true
    }

    fun installDemo() = execute { listOf(activity.services.importer.installDemo()) }

    private fun importAll(uris: List<Uri>) = execute { uris.map { activity.services.importer.import(it) } }

    private fun execute(work: () -> List<ImportResult>) {
        @Suppress("DEPRECATION")
        val progress = ProgressDialog(activity).apply {
            setMessage(activity.getString(R.string.import_working))
            setCancelable(false)
            show()
        }
        activity.scope.launch {
            val results = withContext(Dispatchers.IO) { work() }
            progress.dismiss()
            report(results)
            onImported(results)
        }
    }

    private fun report(results: List<ImportResult>) {
        val failed = results.filterIsInstance<ImportResult.Failed>()
        if (results.size == 1) {
            when (val r = results[0]) {
                is ImportResult.Added -> activity.toast(activity.getString(R.string.import_added, r.entry.title))
                is ImportResult.AlreadyPresent -> activity.toast(activity.getString(R.string.import_already, r.entry.title))
                is ImportResult.Failed -> activity.showMessage(activity.getString(R.string.import_failed_title, r.name), r.message)
            }
            val warnings = (results[0] as? ImportResult.Added)?.warnings.orEmpty()
            if (warnings.isNotEmpty()) activity.toast(warnings.joinToString("\n"))
            return
        }
        val added = results.count { it is ImportResult.Added }
        val present = results.count { it is ImportResult.AlreadyPresent }
        val summary = activity.getString(R.string.import_summary, added, present, failed.size)
        if (failed.isEmpty()) {
            activity.toast(summary)
        } else {
            AlertDialog.Builder(activity)
                .setTitle(summary)
                .setMessage(failed.joinToString("\n\n") { "${it.name}: ${it.message}" })
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    companion object {
        const val REQUEST_CODE = 4101
    }
}
