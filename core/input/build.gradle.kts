// SPDX-License-Identifier: MPL-2.0
// GBA buttons, key mapping and touch layout models. Pure Kotlin/JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}
