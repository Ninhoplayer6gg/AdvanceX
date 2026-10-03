// SPDX-License-Identifier: MPL-2.0
package io.advancex.core.input

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** On-screen control elements. */
@Serializable
enum class ControlId(val label: String, val isOptional: Boolean = false) {
    DPAD("D-pad"),
    A("A"),
    B("B"),
    L("L"),
    R("R"),
    START("Start"),
    SELECT("Select"),
    FAST_FORWARD("Fast-forward", isOptional = true),
    REWIND("Rewind", isOptional = true),
    MENU("Menu"),
}

/**
 * Position and size of one control.
 *
 * Positions are fractions of the controls area (0..1), so a layout adapts to
 * any screen size. [sizeDp] is the diameter (or the long side) in dp; it is
 * clamped to [MIN_SIZE_DP] so controls never become too small to hit.
 */
@Serializable
data class ControlPlacement(
    val id: ControlId,
    val x: Float,
    val y: Float,
    val sizeDp: Float,
    val visible: Boolean = true,
) {
    fun normalized(): ControlPlacement = copy(
        x = x.coerceIn(0f, 1f),
        y = y.coerceIn(0f, 1f),
        sizeDp = sizeDp.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP),
    )

    companion object {
        const val MIN_SIZE_DP = 44f
        const val MAX_SIZE_DP = 260f
    }
}

enum class Orientation { PORTRAIT, LANDSCAPE }

/** A complete touch layout for one orientation. */
@Serializable
data class TouchLayout(
    val name: String,
    val orientation: Orientation,
    val controls: List<ControlPlacement>,
    /** 0.1..1 opacity of the controls. */
    val opacity: Float = 0.75f,
    /** Global multiplier applied to every control size. */
    val scale: Float = 1f,
    val haptics: Boolean = true,
) {
    fun placement(id: ControlId): ControlPlacement? = controls.firstOrNull { it.id == id }

    fun with(placement: ControlPlacement): TouchLayout =
        copy(controls = controls.filterNot { it.id == placement.id } + placement.normalized())

    fun normalized(): TouchLayout {
        val byId = controls.associateBy { it.id }
        val defaults = defaultFor(orientation)
        // Every mandatory control must exist; missing ones come from the default.
        val merged = ControlId.entries.mapNotNull { id ->
            (byId[id] ?: defaults.controls.firstOrNull { it.id == id })?.normalized()
        }
        return copy(
            controls = merged,
            opacity = opacity.coerceIn(0.1f, 1f),
            scale = scale.coerceIn(0.6f, 1.8f),
        )
    }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        fun fromJson(text: String): TouchLayout = json.decodeFromString(serializer(), text).normalized()

        fun defaultFor(orientation: Orientation): TouchLayout =
            if (orientation == Orientation.PORTRAIT) DEFAULT_PORTRAIT else DEFAULT_LANDSCAPE

        /** Portrait: game on top, controls in the lower area (fractions of that area). */
        val DEFAULT_PORTRAIT = TouchLayout(
            name = "Default",
            orientation = Orientation.PORTRAIT,
            controls = listOf(
                ControlPlacement(ControlId.DPAD, 0.24f, 0.50f, 150f),
                ControlPlacement(ControlId.A, 0.86f, 0.42f, 76f),
                ControlPlacement(ControlId.B, 0.68f, 0.56f, 76f),
                ControlPlacement(ControlId.L, 0.12f, 0.08f, 96f),
                ControlPlacement(ControlId.R, 0.88f, 0.08f, 96f),
                ControlPlacement(ControlId.SELECT, 0.38f, 0.90f, 72f),
                ControlPlacement(ControlId.START, 0.62f, 0.90f, 72f),
                ControlPlacement(ControlId.FAST_FORWARD, 0.62f, 0.10f, 52f),
                ControlPlacement(ControlId.REWIND, 0.38f, 0.10f, 52f, visible = false),
                ControlPlacement(ControlId.MENU, 0.50f, 0.10f, 52f),
            ),
        )

        /** Landscape: controls overlay the sides of the game image. */
        val DEFAULT_LANDSCAPE = TouchLayout(
            name = "Default",
            orientation = Orientation.LANDSCAPE,
            controls = listOf(
                ControlPlacement(ControlId.DPAD, 0.11f, 0.62f, 150f),
                ControlPlacement(ControlId.A, 0.94f, 0.56f, 72f),
                ControlPlacement(ControlId.B, 0.85f, 0.70f, 72f),
                ControlPlacement(ControlId.L, 0.07f, 0.14f, 92f),
                ControlPlacement(ControlId.R, 0.93f, 0.14f, 92f),
                ControlPlacement(ControlId.SELECT, 0.42f, 0.93f, 66f),
                ControlPlacement(ControlId.START, 0.58f, 0.93f, 66f),
                ControlPlacement(ControlId.FAST_FORWARD, 0.93f, 0.33f, 50f),
                ControlPlacement(ControlId.REWIND, 0.07f, 0.33f, 50f, visible = false),
                ControlPlacement(ControlId.MENU, 0.50f, 0.07f, 50f),
            ),
            opacity = 0.6f,
        )
    }
}
