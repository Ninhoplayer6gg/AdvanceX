// SPDX-License-Identifier: MPL-2.0
//
// Build modes (gradle property `advancex.android.build`):
//   agp  (default) - standard Android Gradle Plugin build; use this in Android Studio.
//   sdk            - builds the APK directly with the Android SDK tools (aapt2, d8,
//                    apksigner) and the NDK, without AGP. Used where Google's Maven
//                    repository is unreachable (e.g. restricted CI sandboxes).
//   none           - only the platform-independent Kotlin modules (fast unit tests).
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "AdvanceX"

include(":core:rom", ":core:saves", ":core:input", ":core:emulator")
include(":advance-engine")

when (val mode = providers.gradleProperty("advancex.android.build").getOrElse("agp")) {
    "agp" -> include(":app")
    "sdk" -> {
        include(":app-sdk")
        project(":app-sdk").projectDir = file("tools/sdk-build")
    }
    "none" -> Unit
    else -> throw GradleException("Unknown advancex.android.build mode '$mode' (expected agp, sdk or none)")
}
