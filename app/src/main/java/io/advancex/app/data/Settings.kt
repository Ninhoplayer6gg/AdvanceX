// SPDX-License-Identifier: MPL-2.0
package io.advancex.app.data

import io.advancex.core.emulator.EmulationSettings
import io.advancex.core.emulator.GameOverrides
import io.advancex.core.emulator.Log
import io.advancex.core.input.KeyMap
import io.advancex.core.input.Orientation
import io.advancex.core.input.TouchLayout
import io.advancex.core.saves.AtomicFiles
import io.advancex.engine.widescreen.WidescreenMode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Global app settings (`settings.json`). */
@Serializable
data class AppSettings(
    val emulation: EmulationSettings = EmulationSettings(),
    val touchPortrait: TouchLayout = TouchLayout.DEFAULT_PORTRAIT,
    val touchLandscape: TouchLayout = TouchLayout.DEFAULT_LANDSCAPE,
    val touchPresets: List<TouchLayout> = emptyList(),
    val gamepadMap: KeyMap = KeyMap.DEFAULT_GAMEPAD,
    val keyboardMap: KeyMap = KeyMap.DEFAULT_KEYBOARD,
    val hideTouchWithController: Boolean = true,
    val showPerformanceOverlay: Boolean = false,
    /** Advance Studio asset capture while playing (Experimental). */
    val captureSprites: Boolean = false,
    /** Audio backend: 0 auto, 1 AAudio, 2 OpenSL ES. */
    val audioBackend: Int = 0,
) {
    fun touchLayout(orientation: Orientation): TouchLayout =
        if (orientation == Orientation.PORTRAIT) touchPortrait else touchLandscape

    fun withTouchLayout(layout: TouchLayout): AppSettings =
        if (layout.orientation == Orientation.PORTRAIT) copy(touchPortrait = layout) else copy(touchLandscape = layout)
}

/** Per-game configuration (`games/<id>.json`). */
@Serializable
data class GameConfig(
    val overrides: GameOverrides = GameOverrides(),
    /** User choices for individual runtime patches (id -> enabled). */
    val patchToggles: Map<String, Boolean> = emptyMap(),
    val replacementsEnabled: Boolean = true,
    /** Experimental: upscale captured sprites (Scale2x) and use them as replacements. */
    val autoUpscaleSprites: Boolean = false,
    val widescreen: WidescreenMode = WidescreenMode.OFF,
)

internal val SettingsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
    coerceInputValues = true
}

/** A JSON file holding one value, written atomically and cached in memory. */
open class JsonStore<T>(private val file: File, private val serializer: KSerializer<T>, private val default: () -> T) {
    private val listeners = mutableListOf<(T) -> Unit>()

    @Volatile
    var value: T = load()
        private set

    private fun load(): T {
        val data = AtomicFiles.readWithBackup(file) ?: return default()
        return runCatching { SettingsJson.decodeFromString(serializer, data.data.toString(Charsets.UTF_8)) }
            .onFailure { Log.w("Settings", "Could not read ${file.name}; using defaults", it) }
            .getOrElse { default() }
    }

    @Synchronized
    fun update(transform: (T) -> T): T {
        val next = transform(value)
        value = next
        runCatching { AtomicFiles.writeText(file, SettingsJson.encodeToString(serializer, next), keepBackup = true) }
            .onFailure { Log.e("Settings", "Could not save ${file.name}", it) }
        synchronized(listeners) { listeners.toList() }.forEach { it(next) }
        return next
    }

    fun addListener(l: (T) -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: (T) -> Unit) = synchronized(listeners) { listeners -= l }
}

class SettingsStore(file: File) : JsonStore<AppSettings>(file, AppSettings.serializer(), { AppSettings() })

class GameConfigStore(private val dir: File) {
    private val cache = mutableMapOf<String, JsonStore<GameConfig>>()

    @Synchronized
    fun store(gameId: String): JsonStore<GameConfig> =
        cache.getOrPut(gameId) { JsonStore(File(dir, "$gameId.json"), GameConfig.serializer()) { GameConfig() } }

    fun get(gameId: String): GameConfig = store(gameId).value
    fun update(gameId: String, transform: (GameConfig) -> GameConfig) = store(gameId).update(transform)

    fun delete(gameId: String) {
        synchronized(this) { cache.remove(gameId) }
        File(dir, "$gameId.json").delete()
        File(dir, "$gameId.json.bak").delete()
    }
}
