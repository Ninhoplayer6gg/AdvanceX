// SPDX-License-Identifier: MPL-2.0
// Emulator session API contracts and settings resolution. Pure Kotlin/JVM:
// the Android app implements EmulatorSession on top of the native core.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}
