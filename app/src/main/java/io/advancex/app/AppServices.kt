// SPDX-License-Identifier: MPL-2.0
package io.advancex.app

import android.content.Context
import io.advancex.app.bridge.AndroidImageCodec
import io.advancex.app.bridge.AssetContentRoot
import io.advancex.app.data.GameConfigStore
import io.advancex.app.data.GameLibrary
import io.advancex.app.data.RomImporter
import io.advancex.app.data.SettingsStore
import io.advancex.app.util.LogFiles
import io.advancex.core.emulator.Log
import io.advancex.core.saves.SaveLayout
import io.advancex.core.saves.SaveManager
import io.advancex.engine.AdvanceEngine
import io.advancex.engine.content.ContentOrigin
import io.advancex.engine.content.DirectoryContentRoot
import io.advancex.engine.mods.ModManager
import io.advancex.engine.replacements.AssetCache
import io.advancex.engine.safety.EnhancementGuard
import io.advancex.engine.studio.AssetDumpWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Process-wide services (a small service locator; the app has no DI
 * framework). Everything is created lazily on first use.
 *
 * Storage layout under the app's private files directory:
 * ```
 * roms/ covers/ library.json      game library
 * saves/<GAME_ID>/...             cartridge saves, states, screenshots
 * games/<GAME_ID>.json            per-game settings
 * settings.json                   global settings
 * mods/                           installed .advx mods
 * advance/profiles/               user-made profiles
 * studio/                         Advance Studio captures
 * logs/                           logs and local crash reports
 * ```
 */
class AppServices private constructor(context: Context) {
    val app: Context = context.applicationContext
    private val files: File = app.filesDir

    val logs = LogFiles(File(files, "logs"))
    val library by lazy { GameLibrary(files) }
    val importer by lazy { RomImporter(app, library) }
    val settings by lazy { SettingsStore(File(files, "settings.json")) }
    val gameConfigs by lazy { GameConfigStore(File(files, "games")) }
    val saveLayout by lazy { SaveLayout(File(files, "saves")) }
    val saves by lazy { SaveManager(saveLayout) }
    val mods by lazy { ModManager(File(files, "mods")) }
    val userContentDir: File get() = File(files, "advance").apply { mkdirs() }
    val bundledContent by lazy { AssetContentRoot(app.assets, "advance", ContentOrigin.Bundled) }
    val engine by lazy {
        AdvanceEngine { identity ->
            listOf(bundledContent) + mods.contentRoots(identity) + DirectoryContentRoot(userContentDir, ContentOrigin.User)
        }
    }
    val assetCache by lazy { AssetCache(File(app.cacheDir, "assets")) }
    val studio by lazy { AssetDumpWriter(File(files, "studio"), AndroidImageCodec) }
    val guard by lazy { EnhancementGuard(File(files, "session-marker.json")) }
    val biosFile: File get() = File(files, "bios/gba_bios.bin")

    /**
     * Single thread that owns native session open/close. It outlives
     * activities, so closing a game (which writes saves) always completes
     * even if the screen goes away.
     */
    val sessionExecutor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "ax-session") }
    val sessionDispatcher by lazy { sessionExecutor.asCoroutineDispatcher() }

    /** Message for the next screen about a crash detected at startup. */
    @Volatile
    var startupNotice: String? = null

    /**
     * Collects crash evidence from the previous run: a native crash report and
     * an unfinished session marker. If enhancements were active, the game is
     * put into safe mode (Advance Mode disabled until the user re-enables it).
     */
    fun collectPreviousCrash() {
        val nativeCrash = logs.nativeCrashFile
        val hadNativeCrash = nativeCrash.isFile && nativeCrash.length() > 0
        if (hadNativeCrash) {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nativeCrash.lastModified()))
            nativeCrash.renameTo(File(logs.crashesDir, "native-crash-$stamp.txt"))
        }
        val marker = guard.consumeUncleanExit()
        if (marker != null && EnhancementGuard.shouldEnterSafeMode(marker)) {
            gameConfigs.update(marker.gameId) { it.copy(overrides = it.overrides.copy(safeMode = true)) }
            val title = library.get(marker.gameId)?.title ?: marker.gameId
            startupNotice = "AdvanceX closed unexpectedly while \"$title\" was running with enhancements " +
                "(${marker.features.joinToString()}). Enhancements were turned off for this game so it runs " +
                "in original mode. You can turn Advance Mode back on in the game's settings."
            Log.w("Safety", "Unclean exit with enhancements for ${marker.gameId}; safe mode enabled")
        } else if (marker != null || hadNativeCrash) {
            startupNotice = "AdvanceX closed unexpectedly last time. A local crash report was saved " +
                "(Settings → Diagnostics). Your cartridge saves are written as you play and were not affected."
        }
    }

    companion object {
        @Volatile
        private var instance: AppServices? = null

        fun get(context: Context): AppServices =
            instance ?: synchronized(this) { instance ?: AppServices(context).also { instance = it } }
    }
}
