// SPDX-License-Identifier: MPL-2.0
package io.advancex.app.ui.settings

import android.app.AlertDialog
import android.content.Intent
import android.content.res.AssetManager
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import io.advancex.app.R
import io.advancex.app.ui.common.BaseActivity
import io.advancex.app.ui.common.FlowLayout
import io.advancex.app.ui.common.Format
import io.advancex.app.ui.common.Nav
import io.advancex.app.ui.common.Palette
import io.advancex.app.ui.common.TextStyle
import io.advancex.app.ui.common.add
import io.advancex.app.ui.common.card
import io.advancex.app.ui.common.chip
import io.advancex.app.ui.common.dangerButton
import io.advancex.app.ui.common.dp
import io.advancex.app.ui.common.horizontal
import io.advancex.app.ui.common.primaryButton
import io.advancex.app.ui.common.secondaryButton
import io.advancex.app.ui.common.text
import io.advancex.app.ui.common.vertical
import io.advancex.core.rom.RomIdentity
import io.advancex.engine.mods.Compatibility
import io.advancex.engine.mods.InstalledMod
import io.advancex.engine.mods.ModArchive
import io.advancex.engine.mods.ModException
import io.advancex.engine.mods.ModPackager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Mod manager: import, enable, disable, remove, version and compatibility. */
class ModsActivity : BaseActivity() {
    private lateinit var column: LinearLayout
    private var identity: RomIdentity? = null
    private var gameTitle: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(Nav.EXTRA_GAME_ID)?.let { id ->
            services.library.get(id)?.let {
                identity = it.identity()
                gameTitle = it.title
            }
        }
        column = contentColumn {}
        setScreen(getString(R.string.mods_title), column)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        scope.launch {
            val mods = withContext(Dispatchers.IO) { services.mods.installed() }
            column.removeAllViews()
            column.add(primaryButton(getString(R.string.mods_install), R.drawable.ic_import) {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQUEST_INSTALL)
            }, bottomMarginDp = 10)
            if (mods.none { it.id == DEMO_ID }) {
                column.add(secondaryButton(getString(R.string.mods_install_demo), R.drawable.ic_mods) { installDemo() }, bottomMarginDp = 16)
            }
            if (mods.isEmpty()) {
                column.addView(card { addView(text(getString(R.string.mods_empty), TextStyle.BODY_SECONDARY)) })
            }
            for (mod in mods) column.add(modCard(mod), bottomMarginDp = 12)
        }
    }

    private fun modCard(mod: InstalledMod) = card {
        val m = mod.manifest
        val header = horizontal {
            val titles = vertical {
                addView(text(m.name, TextStyle.HEADLINE))
                addView(text(getString(R.string.mods_by, m.version.toString(), m.author.ifBlank { "unknown" }), TextStyle.CAPTION))
            }
            add(titles, width = 0, weight = 1f)
            addView(Switch(context).apply {
                isChecked = mod.enabled
                setOnCheckedChangeListener { _, on ->
                    scope.launch {
                        withContext(Dispatchers.IO) { services.mods.setEnabled(mod.id, on) }
                        render()
                    }
                }
            })
        }
        addView(header)
        if (m.description.isNotBlank()) add(text(m.description, TextStyle.BODY_SECONDARY), topMarginDp = 8)
        val c = mod.contents
        add(text(getString(R.string.mods_contents, c.profiles, c.patches, c.sprites, c.textures, c.audio, c.shaders) +
            " · " + Format.bytes(c.totalBytes), TextStyle.CAPTION), topMarginDp = 8)
        val chips = FlowLayout(context, dp(6))
        val compat = services.mods.compatibility(mod, identity)
        val compatColor = when (compat) {
            Compatibility.COMPATIBLE, Compatibility.UNIVERSAL -> Palette.success(context)
            Compatibility.WRONG_GAME -> if (identity == null) Palette.textSecondary(context) else Palette.warning(context)
            Compatibility.NEEDS_NEWER_APP -> Palette.danger(context)
        }
        if (!(compat == Compatibility.WRONG_GAME && identity == null)) chips.addView(chip(compat.label, compatColor))
        if (!m.isUniversal) chips.addView(chip(getString(R.string.mods_compatible_with, m.compatibleGames.joinToString { it.describe() }), Palette.cyan(context)))
        if (c.containsScripts) chips.addView(chip(getString(R.string.mods_scripts_warning), Palette.warning(context)))
        add(chips, topMarginDp = 8)
        add(dangerButton(getString(R.string.action_remove)) {
            confirm(getString(R.string.mods_remove_title, m.name), m.description.ifBlank { m.name }, getString(R.string.action_remove)) {
                scope.launch {
                    withContext(Dispatchers.IO) { services.mods.remove(mod.id) }
                    render()
                }
            }
        }.also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) },
            width = ViewGroup.LayoutParams.WRAP_CONTENT, topMarginDp = 12)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (requestCode == REQUEST_INSTALL && resultCode == RESULT_OK && uri != null) installFrom(uri)
    }

    private fun installFrom(uri: Uri) {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File.createTempFile("mod", ".advx", cacheDir)
                    contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: error("unreadable")
                    tmp
                }.getOrNull()
            }
            if (file == null) {
                showMessage(getString(R.string.mods_install), "The file could not be read.")
                return@launch
            }
            inspectAndInstall(file)
        }
    }

    /** Builds the bundled demo pack (shipped as a source folder) into a real .advx and installs it. */
    private fun installDemo() {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(cacheDir, "demo-pack").apply { deleteRecursively(); mkdirs() }
                    copyAssetDir(assets, "demo/advancex-demo-pack", dir)
                    val out = File(cacheDir, "advancex-demo-pack.advx")
                    ModPackager.pack(dir, out)
                    out
                }.getOrElse { null }
            }
            if (file == null) showMessage(getString(R.string.mods_install_demo), "The demo pack is missing from this build.")
            else inspectAndInstall(file)
        }
    }

    private suspend fun inspectAndInstall(file: File) {
        val inspection = withContext(Dispatchers.IO) { runCatching { ModArchive.inspect(file) } }
        val info = inspection.getOrElse {
            file.delete()
            showMessage(getString(R.string.mods_install), (it as? ModException)?.message ?: "Invalid package.")
            return
        }
        val m = info.manifest
        val message = buildString {
            appendLine(getString(R.string.mods_by, m.version.toString(), m.author.ifBlank { "unknown" }))
            if (m.description.isNotBlank()) appendLine().appendLine(m.description)
            appendLine()
            appendLine(getString(R.string.mods_contents, info.contents.profiles, info.contents.patches, info.contents.sprites,
                info.contents.textures, info.contents.audio, info.contents.shaders))
            appendLine(if (m.isUniversal) "Works with any game." else getString(R.string.mods_compatible_with, m.compatibleGames.joinToString { it.describe() }))
            if (!m.supportsThisAdvanceX()) appendLine().appendLine("Requires AdvanceX ${m.advanceXVersion}.")
            if (info.contents.containsScripts) appendLine().appendLine(getString(R.string.mods_scripts_warning))
            if (info.ignored.isNotEmpty()) appendLine().appendLine(getString(R.string.mods_ignored_warning, info.ignored.size))
        }
        AlertDialog.Builder(this)
            .setTitle(m.name)
            .setMessage(message)
            .setPositiveButton(R.string.action_install) { _, _ ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { services.mods.install(file) }.also { file.delete() } }
                    result.onSuccess { toast(getString(R.string.mods_installed, it.manifest.name)) }
                        .onFailure { showMessage(getString(R.string.mods_install), it.message ?: "Install failed") }
                    render()
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> file.delete() }
            .show()
    }

    private fun copyAssetDir(assets: AssetManager, path: String, target: File) {
        val children = assets.list(path) ?: emptyArray()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        for (child in children) copyAssetDir(assets, "$path/$child", File(target, child))
    }

    companion object {
        private const val REQUEST_INSTALL = 4601
        private const val DEMO_ID = "advancex-demo-pack"
    }
}
