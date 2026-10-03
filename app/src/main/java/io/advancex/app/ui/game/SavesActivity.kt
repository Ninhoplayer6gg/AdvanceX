// SPDX-License-Identifier: MPL-2.0
package io.advancex.app.ui.game

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import io.advancex.app.R
import io.advancex.app.ui.common.BaseActivity
import io.advancex.app.ui.common.Format
import io.advancex.app.ui.common.Nav
import io.advancex.app.ui.common.Palette
import io.advancex.app.ui.common.TextStyle
import io.advancex.app.ui.common.add
import io.advancex.app.ui.common.card
import io.advancex.app.ui.common.dp
import io.advancex.app.ui.common.dpf
import io.advancex.app.ui.common.roundedBackground
import io.advancex.app.ui.common.secondaryButton
import io.advancex.app.ui.common.sectionHeader
import io.advancex.app.ui.common.text
import io.advancex.core.saves.StateSlotInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Cartridge save management (backups, import/export) and save-state slots. */
class SavesActivity : BaseActivity() {
    private lateinit var gameId: String
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gameId = intent.getStringExtra(Nav.EXTRA_GAME_ID) ?: return finish()
        column = contentColumn {}
        setScreen(getString(R.string.saves_title), column)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        column.removeAllViews()
        val cart = services.saveLayout.cartridgeSave(gameId)
        column.addView(sectionHeader(getString(R.string.saves_cartridge)))
        column.addView(card {
            if (cart.isFile) {
                addView(text("${Format.bytes(cart.length())} · ${Format.dateTime(context, cart.lastModified())}", TextStyle.HEADLINE))
                add(text("${services.saves.cartridgeBackups(gameId).size} backups kept", TextStyle.CAPTION), topMarginDp = 4)
            } else {
                addView(text(getString(R.string.saves_cartridge_none), TextStyle.BODY_SECONDARY))
            }
            add(secondaryButton(getString(R.string.saves_backup_now), R.drawable.ic_saves) {
                io { services.saves.snapshotCartridge(gameId) }.also { toast(getString(R.string.action_done)) }
            }, topMarginDp = 14)
            add(secondaryButton(getString(R.string.saves_restore), R.drawable.ic_reset) { restoreBackup() }, topMarginDp = 8)
            add(secondaryButton(getString(R.string.saves_import), R.drawable.ic_import) {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQUEST_IMPORT)
            }, topMarginDp = 8)
            add(secondaryButton(getString(R.string.saves_export), R.drawable.ic_folder) {
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip").putExtra(Intent.EXTRA_TITLE, "$gameId-saves.zip")
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_EXPORT)
            }, topMarginDp = 8)
        })

        column.addView(sectionHeader(getString(R.string.saves_states)))
        column.addView(text(getString(R.string.saves_states_note), TextStyle.CAPTION).apply { setPadding(dp(4), 0, dp(4), dp(10)) })
        val grid = GridLayout(this).apply { columnCount = if (resources.configuration.screenWidthDp > 600) 4 else 2 }
        for (slot in services.saves.slots(gameId)) {
            val params = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0
                setMargins(dp(5), dp(5), dp(5), dp(5))
            }
            grid.addView(slotCard(slot), params)
        }
        column.addView(grid)
    }

    private fun slotCard(info: StateSlotInfo) = card(onClick = if (info.exists) ({ slotMenu(info) }) else null, paddingDp = 10) {
        val thumb = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = roundedBackground(Palette.bg(context), dpf(10))
            info.screenshot?.let { f ->
                BitmapFactory.decodeFile(f.absolutePath)?.let { bmp ->
                    setImageDrawable(BitmapDrawable(resources, bmp).apply { isFilterBitmap = false })
                }
            }
        }
        add(thumb, height = dp(96))
        add(text(info.slot.label, TextStyle.HEADLINE), topMarginDp = 8)
        val subtitle = info.header?.let { Format.dateTime(context, it.timestampMs) } ?: getString(R.string.saves_slot_empty)
        addView(text(subtitle, TextStyle.CAPTION))
    }

    private fun slotMenu(info: StateSlotInfo) {
        AlertDialog.Builder(this)
            .setTitle(info.slot.label)
            .setItems(arrayOf(getString(R.string.saves_load), getString(R.string.action_delete))) { _, which ->
                when (which) {
                    0 -> Nav.play(this, gameId, stateSlot = info.slot.index)
                    1 -> confirm(getString(R.string.saves_delete_state), info.slot.label, getString(R.string.action_delete)) {
                        services.saves.deleteState(gameId, info.slot)
                        render()
                    }
                }
            }
            .show()
    }

    private fun restoreBackup() {
        val backups = services.saves.cartridgeBackups(gameId)
        if (backups.isEmpty()) {
            showMessage(getString(R.string.saves_restore), getString(R.string.saves_backups_none))
            return
        }
        val labels = backups.map { "${Format.dateTime(this, it.createdAtMs)} · ${Format.bytes(it.size)}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.saves_restore)
            .setItems(labels) { _, which ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { services.saves.restoreCartridgeBackup(gameId, backups[which]) }.isSuccess }
                    toast(if (ok) getString(R.string.saves_restored) else "Restore failed")
                    render()
                }
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQUEST_EXPORT -> scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { contentResolver.openOutputStream(uri)?.use { services.saves.exportZip(gameId, it) } }.isSuccess
                }
                toast(if (ok) getString(R.string.saves_exported) else "Export failed")
            }
            REQUEST_IMPORT -> scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("unreadable")
                        services.saves.importCartridgeSave(gameId, bytes)
                    }
                }
                result.onSuccess { toast(getString(R.string.saves_imported)) }
                    .onFailure { showMessage(getString(R.string.saves_import), it.message ?: "Import failed") }
                render()
            }
        }
    }

    private fun io(block: () -> Unit) = scope.launch {
        withContext(Dispatchers.IO) { block() }
        render()
    }

    companion object {
        private const val REQUEST_IMPORT = 4301
        private const val REQUEST_EXPORT = 4302
    }
}
