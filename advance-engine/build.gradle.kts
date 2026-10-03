// SPDX-License-Identifier: MPL-2.0
// Advance Engine: profiles, runtime patches, .advx mods, asset replacement,
// widescreen, shader presets. Pure Kotlin/JVM; never required for emulation.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:rom"))
    api(project(":core:emulator"))
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}
