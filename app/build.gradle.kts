// SPDX-License-Identifier: MPL-2.0
//
// Standard Android build (Android Gradle Plugin). Open the repository in
// Android Studio, or run: ./gradlew :app:assembleDebug
//
// The same sources are also built by tools/sdk-build (AGP-free) in
// environments without access to Google's Maven repository.
import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "io.advancex.app"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "io.github.ninhoplayer6gg.advancex"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = rootProject.version.toString()

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
                targets += "advancex"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Release signing is configured by the publisher (see docs/BUILDING.md).
        }
        debug {
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            // Bundled Advance Engine content (profiles etc.).
            assets.srcDir("../assets")
        }
    }

    packaging {
        jniLibs {
            // Keep libadvancex.so uncompressed and page-aligned in the APK.
            useLegacyPackaging = false
        }
    }

    buildFeatures {
        buildConfig = false
    }
}

/** Copies the demo cartridge, demo mod source and license texts into generated assets. */
abstract class PrepareAdvanceXAssets @Inject constructor(private val fs: FileSystemOperations) : DefaultTask() {
    @get:InputDirectory
    abstract val testRomDir: DirectoryProperty

    @get:InputDirectory
    abstract val demoPack: DirectoryProperty

    @get:InputFiles
    abstract val licenses: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        fs.sync {
            into(outputDir)
            from(testRomDir) {
                include("*.gba")
                into("demo")
            }
            from(demoPack) { into("demo/advancex-demo-pack") }
            from(licenses) { into("licenses") }
        }
    }
}

val prepareAdvanceXAssets = tasks.register<PrepareAdvanceXAssets>("prepareAdvanceXAssets") {
    testRomDir.set(rootProject.layout.projectDirectory.dir("tools/testrom/dist"))
    demoPack.set(rootProject.layout.projectDirectory.dir("mods/examples/advancex-demo-pack"))
    licenses.from(rootProject.file("THIRD_PARTY_LICENSES.md"), rootProject.file("LICENSE"))
    outputDir.set(layout.buildDirectory.dir("generated/advancex-assets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareAdvanceXAssets, PrepareAdvanceXAssets::outputDir)
    }
}

dependencies {
    implementation(project(":core:rom"))
    implementation(project(":core:saves"))
    implementation(project(":core:input"))
    implementation(project(":core:emulator"))
    implementation(project(":advance-engine"))
    implementation(libs.kotlinx.coroutines.android)
}
