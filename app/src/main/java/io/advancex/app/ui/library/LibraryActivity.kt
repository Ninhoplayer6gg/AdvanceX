// SPDX-License-Identifier: MPL-2.0
package io.advancex.app.ui.library

import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import io.advancex.app.R
import io.advancex.app.data.GameEntry
import io.advancex.app.data.ImportResult
import io.advancex.app.ui.common.BaseActivity
import io.advancex.app.ui.common.CoverView
import io.advancex.app.ui.common.Format
import io.advancex.app.ui.common.ImportFlow
import io.advancex.app.ui.common.Nav
import io.advancex.app.ui.common.Palette
import io.advancex.app.ui.common.TextStyle
import io.advancex.app.ui.common.add
import io.advancex.app.ui.common.choiceRow
import io.advancex.app.ui.common.dp
import io.advancex.app.ui.common.dpf
import io.advancex.app.ui.common.frame
import io.advancex.app.ui.common.horizontal
import io.advancex.app.ui.common.iconButton
import io.advancex.app.ui.common.primaryButton
import io.advancex.app.ui.common.roundedBackground
import io.advancex.app.ui.common.text
import io.advancex.app.ui.common.vertical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream

/** Game Library: cover grid with search, favorites and recent filters. */
class LibraryActivity : BaseActivity() {
    private enum class Filter { ALL, FAVORITES, RECENT }

    private var filter = Filter.ALL
    private var query = ""
    private val adapter = GameAdapter()
    private lateinit var grid: GridView
    private lateinit var emptyContainer: LinearLayout
    private val importFlow by lazy { ImportFlow(this) { onImported(it) } }
    private var coverTarget: String? = null
    private val libraryListener: () -> Unit = { runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val search = EditText(this).apply {
            hint = getString(R.string.library_search_hint)
            setHintTextColor(Palette.textTertiary(context))
            setTextColor(Palette.text(context))
            isSingleLine = true
            background = roundedBackground(Palette.surface(context), dpf(24), Palette.outline(context), dp(1))
            setPadding(dp(18), dp(12), dp(18), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    refresh()
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            })
        }
        val filters = choiceRow(
            getString(R.string.library_title), null,
            listOf(getString(R.string.library_filter_all), getString(R.string.library_filter_favorites), getString(R.string.library_filter_recent)),
            0,
        ) { index ->
            filter = Filter.entries[index]
            refresh()
        }.apply { (getChildAt(0) as View).visibility = View.GONE; background = null; setPadding(0, 0, 0, 0) }

        grid = GridView(this).apply {
            adapter = this@LibraryActivity.adapter
            horizontalSpacing = dp(12)
            verticalSpacing = dp(12)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(96))
            selector = android.graphics.drawable.ColorDrawable(0)
            setOnItemClickListener { _, _, pos, _ -> Nav.game(context, this@LibraryActivity.adapter.getItem(pos).id) }
            setOnItemLongClickListener { _, _, pos, _ ->
                showGameMenu(this@LibraryActivity.adapter.getItem(pos))
                true
            }
        }
        emptyContainer = vertical()

        val content = vertical {
            setPadding(dp(16), 0, dp(16), 0)
            addView(search)
            add(filters, topMarginDp = 4)
            add(frame {
                addView(grid)
                addView(emptyContainer)
            }, height = 0, weight = 1f)
        }
        val importButton = iconButton(R.drawable.ic_add, getString(R.string.action_import), Palette.cyan(this)) { importFlow.start() }
        setScreen(getString(R.string.library_title), content, actions = listOf(importButton), scroll = false)
        updateColumns()
        services.library.addListener(libraryListener)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        services.library.removeListener(libraryListener)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateColumns()
    }

    private fun updateColumns() {
        val widthDp = resources.configuration.screenWidthDp
        grid.numColumns = (widthDp / 170).coerceIn(2, 6)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (importFlow.onActivityResult(requestCode, resultCode, data)) return
        if (requestCode == REQUEST_COVER && resultCode == RESULT_OK && data?.data != null) {
            val id = coverTarget ?: return
            val uri = data.data!!
            scope.launch {
                val ok = withContext(Dispatchers.IO) { saveCover(id, uri) }
                if (!ok) toast("Could not use that image")
                refresh()
            }
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun onImported(results: List<ImportResult>) {
        refresh()
        val added = results.filterIsInstance<ImportResult.Added>()
        if (added.size == 1) Nav.game(this, added[0].entry.id)
    }

    private fun refresh() {
        val q = query.trim().lowercase()
        var games = services.library.games
        if (q.isNotEmpty()) games = games.filter { q in it.title.lowercase() || q in it.gameCode.lowercase() }
        games = when (filter) {
            Filter.ALL -> games.sortedWith(compareByDescending<GameEntry> { it.favorite }.thenBy { it.title.lowercase() })
            Filter.FAVORITES -> games.filter { it.favorite }.sortedBy { it.title.lowercase() }
            Filter.RECENT -> games.filter { it.lastPlayedAtMs > 0 }.sortedByDescending { it.lastPlayedAtMs }
        }
        adapter.games = games
        adapter.notifyDataSetChanged()
        emptyContainer.removeAllViews()
        if (games.isEmpty()) {
            val empty = if (services.library.games.isEmpty()) {
                emptyState(R.drawable.ic_library, getString(R.string.library_empty_title), getString(R.string.library_empty_body),
                    primaryButton(getString(R.string.action_import), R.drawable.ic_import) { importFlow.start() })
            } else {
                emptyState(R.drawable.ic_search, getString(R.string.library_no_results), "", null)
            }
            emptyContainer.addView(empty)
        }
    }

    private fun showGameMenu(game: GameEntry) {
        val options = mutableListOf(
            getString(R.string.action_play) to { Nav.play(this, game.id) },
            (if (game.favorite) getString(R.string.library_favorite_remove) else getString(R.string.library_favorite_add)) to {
                toggleFavorite(game)
            },
            getString(R.string.action_rename) to { rename(game) },
            getString(R.string.library_set_cover) to { pickCover(game) },
        )
        if (game.hasCustomCover) {
            options += getString(R.string.library_reset_cover) to {
                scope.launch(Dispatchers.IO) { services.library.update(game.id) { it.copy(hasCustomCover = false) } }
                Unit
            }
        }
        options += getString(R.string.action_remove) to { remove(game) }
        AlertDialog.Builder(this)
            .setTitle(game.title)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .show()
    }

    private fun toggleFavorite(game: GameEntry) {
        scope.launch(Dispatchers.IO) { services.library.update(game.id) { it.copy(favorite = !it.favorite) } }
    }

    private fun rename(game: GameEntry) {
        val input = EditText(this).apply {
            setText(game.title)
            setSelection(game.title.length)
            isSingleLine = true
        }
        val container = frame { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_rename)
            .setView(container)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = input.text.toString().trim().take(80)
                if (name.isNotEmpty()) scope.launch(Dispatchers.IO) { services.library.update(game.id) { it.copy(title = name) } }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickCover(game: GameEntry) {
        coverTarget = game.id
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_COVER)
    }

    private fun saveCover(id: String, uri: android.net.Uri): Boolean {
        val game = services.library.get(id) ?: return false
        val bitmap = runCatching { contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull() ?: return false
        val scale = minOf(1f, 768f / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        val target = services.library.customCover(game)
        FileOutputStream(target).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        CoverView.invalidate(target)
        services.library.update(id) { it.copy(hasCustomCover = true) }
        return true
    }

    private fun remove(game: GameEntry) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.library_remove_title, game.title))
            .setMessage(R.string.library_remove_body)
            .setPositiveButton(R.string.library_remove_keep_saves) { _, _ -> doRemove(game, false) }
            .setNeutralButton(R.string.library_remove_delete_saves) { _, _ ->
                confirm(getString(R.string.library_remove_delete_saves), getString(R.string.library_remove_title, game.title),
                    getString(R.string.action_delete)) { doRemove(game, true) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doRemove(game: GameEntry, deleteSaves: Boolean) {
        scope.launch(Dispatchers.IO) {
            services.library.remove(game.id, services.saveLayout.gameDir(game.id), deleteSaves)
            if (deleteSaves) services.gameConfigs.delete(game.id)
        }
    }

    private inner class GameAdapter : BaseAdapter() {
        var games: List<GameEntry> = emptyList()
        override fun getCount() = games.size
        override fun getItem(position: Int) = games[position]
        override fun getItemId(position: Int) = games[position].id.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val game = games[position]
            val holder = (convertView?.tag as? Holder) ?: Holder()
            holder.bind(game)
            return holder.root
        }

        private inner class Holder {
            val cover = CoverView(this@LibraryActivity).apply { aspect = 1.15f }
            val star = ImageView(this@LibraryActivity).apply {
                val p = dp(10)
                setPadding(p, p, p, p)
            }
            val subtitle = text("", TextStyle.CAPTION)
            val root: LinearLayout = vertical {
                addView(frame {
                    addView(cover)
                    addView(star, android.widget.FrameLayout.LayoutParams(dp(44), dp(44), android.view.Gravity.END or android.view.Gravity.TOP))
                })
                add(subtitle, topMarginDp = 6, startMarginDp = 4)
                tag = this@Holder
            }

            fun bind(game: GameEntry) {
                cover.bind(game, services.library.coverFile(game))
                star.setImageResource(if (game.favorite) R.drawable.ic_star_filled else R.drawable.ic_star)
                star.imageTintList = android.content.res.ColorStateList.valueOf(
                    if (game.favorite) Palette.warning(this@LibraryActivity) else 0xCCFFFFFF.toInt(),
                )
                star.setOnClickListener { toggleFavorite(game) }
                star.contentDescription = getString(if (game.favorite) R.string.library_favorite_remove else R.string.library_favorite_add)
                subtitle.text = if (game.lastPlayedAtMs > 0) "${Format.relative(game.lastPlayedAtMs)} · ${Format.duration(game.playTimeMs)}"
                else getString(R.string.library_never_played)
            }
        }
    }

    companion object {
        private const val REQUEST_COVER = 4201
    }
}
