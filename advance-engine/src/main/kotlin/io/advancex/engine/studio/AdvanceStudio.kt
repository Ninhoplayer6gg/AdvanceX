// SPDX-License-Identifier: MPL-2.0
package io.advancex.engine.studio

import io.advancex.engine.AdvanceJson
import io.advancex.engine.graphics.FeatureStatus
import io.advancex.engine.replacements.ArgbImage
import io.advancex.engine.replacements.ImageCodec
import io.advancex.engine.replacements.ReplacementEntryDefinition
import io.advancex.engine.replacements.ReplacementPackFile
import java.io.File

/**
 * Advance Studio: in-app tools for building enhancement packs. The tool list
 * is the roadmap; each entry states honestly whether it works yet.
 */
enum class StudioTool(val title: String, val description: String, val status: FeatureStatus) {
    GAME_PROFILE("Game Profile", "Create a profile pinned to this exact ROM.", FeatureStatus.COMING_LATER),
    ASSET_INSPECTOR("Asset Inspector", "Capture every sprite the game draws, with its fingerprint.", FeatureStatus.EXPERIMENTAL),
    SPRITE_REPLACEMENT("Sprite Replacement", "Map captured sprites to high-resolution artwork.", FeatureStatus.EXPERIMENTAL),
    TILE_REPLACEMENT("Tile Replacement", "Replace background tiles.", FeatureStatus.COMING_LATER),
    RUNTIME_PATCHES("Runtime Patches", "Write and test memory/ROM patches live.", FeatureStatus.COMING_LATER),
    AUDIO("Audio", "Replace music and sound effects.", FeatureStatus.COMING_LATER),
    SHADERS("Shaders", "Tune and save shader presets.", FeatureStatus.COMING_LATER),
    MODS("Mods", "Organise pack contents.", FeatureStatus.COMING_LATER),
    EXPORT_ADVX("Export .advx", "Package a folder as an installable mod.", FeatureStatus.EXPERIMENTAL),
}

/** A sprite captured by the native asset dump. */
data class DumpedAsset(val key: Long, val image: ArgbImage) {
    val keyHex: String get() = "%016x".format(key)
}

/**
 * Writes captured sprites to `<root>/<gameId>/sprites/<key>.png` and keeps a
 * ready-to-edit `replacements/sprites.json` template next to them, so a pack
 * author only has to draw the high-resolution files.
 */
class AssetDumpWriter(private val root: File, private val codec: ImageCodec) {
    fun spritesDir(gameId: String) = File(root, "$gameId/sprites")
    fun templateFile(gameId: String) = File(root, "$gameId/replacements/sprites.json")

    /** Returns true if the asset was new. */
    fun write(gameId: String, asset: DumpedAsset): Boolean {
        val dir = spritesDir(gameId)
        dir.mkdirs()
        val png = File(dir, "${asset.keyHex}.png")
        if (png.exists()) return false
        png.writeBytes(codec.encodePng(asset.image))
        updateTemplate(gameId)
        return true
    }

    fun count(gameId: String): Int = spritesDir(gameId).listFiles { f -> f.name.endsWith(".png") }?.size ?: 0

    private fun updateTemplate(gameId: String) {
        val keys = spritesDir(gameId).listFiles { f -> f.name.endsWith(".png") }?.map { it.nameWithoutExtension }?.sorted()
            ?: return
        val pack = ReplacementPackFile(
            kind = "sprite",
            entries = keys.map { ReplacementEntryDefinition(key = it, file = "../sprites_hd/$it.png", note = "draw me") },
        )
        val f = templateFile(gameId)
        f.parentFile?.mkdirs()
        f.writeText(AdvanceJson.encodeToString(ReplacementPackFile.serializer(), pack))
    }
}
