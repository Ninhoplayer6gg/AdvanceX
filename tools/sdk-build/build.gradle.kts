// SPDX-License-Identifier: MPL-2.0
//
// AGP-free APK build (`-Padvancex.android.build=sdk`).
//
// Produces a signed debug APK from exactly the same sources as the standard
// AGP build (app/src/main, native/, assets/), using only the Android SDK
// command-line tools: aapt2 (resources), d8 (dex), zipalign, apksigner and the
// NDK + CMake for libadvancex.so. It exists for environments where Google's
// Maven repository (which hosts AGP) is unreachable, and doubles as a
// transparent, reproducible reference of what goes into the APK.
//
//   ./gradlew -Padvancex.android.build=sdk :app-sdk:assembleSdkDebug
import java.io.FileInputStream
import java.util.Properties
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val appDir = rootProject.file("app")
val outDir = layout.buildDirectory.dir("sdk").get().asFile

val applicationId = "io.github.ninhoplayer6gg.advancex"
val namespace = "io.advancex.app"
val versionName = rootProject.version.toString()
val versionCode = 1
val minSdk = libs.versions.minSdk.get()
val targetSdk = libs.versions.targetSdk.get()
val compileSdk = libs.versions.compileSdk.get()
val abis = (findProperty("advancex.abis") as String? ?: "arm64-v8a,armeabi-v7a,x86_64").split(',').map { it.trim() }

// --- SDK discovery -----------------------------------------------------------
val sdkDir: File = run {
    val local = rootProject.file("local.properties")
    val fromLocal = if (local.isFile) Properties().apply { FileInputStream(local).use { load(it) } }.getProperty("sdk.dir") else null
    val path = fromLocal ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: throw GradleException("Android SDK not found: set sdk.dir in local.properties or ANDROID_HOME")
    File(path)
}
val androidJar = File(sdkDir, "platforms/android-$compileSdk/android.jar")
val buildToolsDir: File = run {
    val requested = findProperty("advancex.buildTools") as String?
    val dir = File(sdkDir, "build-tools")
    val chosen = requested ?: dir.listFiles()?.map { it.name }?.filter { it[0].isDigit() }
        ?.maxWithOrNull(compareBy<String>({ it.split('.')[0].toInt() }, { it.split('.').getOrNull(1)?.toIntOrNull() ?: 0 }, { it.split('.').getOrNull(2)?.toIntOrNull() ?: 0 }))
        ?: throw GradleException("No build-tools installed in $dir")
    File(dir, chosen)
}
val ndkDir = File(sdkDir, "ndk/${libs.versions.ndk.get()}")
val cmakeExe: String = File(sdkDir, "cmake").listFiles()?.sortedByDescending { it.name }
    ?.map { File(it, "bin/cmake") }?.firstOrNull { it.canExecute() }?.absolutePath ?: "cmake"
fun tool(name: String) = File(buildToolsDir, name).absolutePath

// --- Kotlin compilation against android.jar --------------------------------------
val generatedR = layout.buildDirectory.dir("generated/sdk-r").get().asFile
sourceSets {
    main {
        kotlin.srcDir(appDir.resolve("src/main/java"))
        java.srcDir(generatedR)
    }
}

dependencies {
    compileOnly(files(androidJar))
    implementation(project(":core:rom"))
    implementation(project(":core:saves"))
    implementation(project(":core:input"))
    implementation(project(":core:emulator"))
    implementation(project(":advance-engine"))
    implementation(libs.kotlinx.coroutines.android)
}

// --- Assets (same set the AGP build packages) --------------------------------------
val prepareAssets by tasks.registering(Sync::class) {
    into(outDir.resolve("assets"))
    from(rootProject.file("assets"))
    from(rootProject.file("tools/testrom/dist")) {
        include("*.gba")
        into("demo")
    }
    from(rootProject.file("mods/examples/advancex-demo-pack")) { into("demo/advancex-demo-pack") }
    from(rootProject.file("THIRD_PARTY_LICENSES.md")) { into("licenses") }
    from(rootProject.file("LICENSE")) { into("licenses") }
}

// --- Resources ------------------------------------------------------------------
val mergedManifest = outDir.resolve("AndroidManifest.xml")
val prepareManifest by tasks.registering {
    val source = appDir.resolve("src/main/AndroidManifest.xml")
    inputs.file(source)
    outputs.file(mergedManifest)
    doLast {
        val text = source.readText().replaceFirst(
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">",
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$applicationId\">",
        )
        check(text.contains("package=\"$applicationId\"")) { "manifest header not recognised" }
        mergedManifest.parentFile.mkdirs()
        mergedManifest.writeText(text)
    }
}

val compiledRes = outDir.resolve("compiled-res.zip")
val aapt2Compile by tasks.registering(Exec::class) {
    val resDir = appDir.resolve("src/main/res")
    inputs.dir(resDir)
    outputs.file(compiledRes)
    doFirst { outDir.mkdirs() }
    commandLine(tool("aapt2"), "compile", "--dir", resDir.absolutePath, "-o", compiledRes.absolutePath)
}

val linkedApk = outDir.resolve("resources.ap_")
val aapt2Link by tasks.registering(Exec::class) {
    dependsOn(aapt2Compile, prepareManifest, prepareAssets)
    inputs.files(compiledRes, mergedManifest)
    inputs.dir(outDir.resolve("assets"))
    outputs.file(linkedApk)
    outputs.dir(generatedR)
    doFirst { generatedR.mkdirs() }
    commandLine(
        tool("aapt2"), "link",
        "-o", linkedApk.absolutePath,
        "-I", androidJar.absolutePath,
        "--manifest", mergedManifest.absolutePath,
        "-A", outDir.resolve("assets").absolutePath,
        "--java", generatedR.absolutePath,
        "--custom-package", namespace,
        "--min-sdk-version", minSdk,
        "--target-sdk-version", targetSdk,
        "--version-code", versionCode.toString(),
        "--version-name", versionName,
        "--debug-mode",
        "--auto-add-overlay",
        "--no-compress-regex", "(\\.gba|resources\\.arsc)$",
        compiledRes.absolutePath,
    )
}
tasks.named("compileKotlin") { dependsOn(aapt2Link) }
tasks.named("compileJava") { dependsOn(aapt2Link) }

// --- Dex ----------------------------------------------------------------------------
val appJar by tasks.registering(Jar::class) {
    archiveFileName.set("app-classes.jar")
    destinationDirectory.set(outDir)
    from(sourceSets.main.get().output)
}

val dexDir = outDir.resolve("dex")
val dex by tasks.registering(Exec::class) {
    dependsOn(appJar)
    val runtime = configurations.runtimeClasspath.get()
    inputs.files(appJar, runtime)
    outputs.dir(dexDir)
    doFirst {
        dexDir.deleteRecursively()
        dexDir.mkdirs()
        val inputsList = listOf(outDir.resolve("app-classes.jar")) + runtime.files
        commandLine(
            listOf(tool("d8"), "--debug", "--min-api", minSdk, "--lib", androidJar.absolutePath, "--output", dexDir.absolutePath) +
                inputsList.map { it.absolutePath },
        )
    }
    commandLine("true") // replaced in doFirst
}

// --- Native library ----------------------------------------------------------------------
val nativeTasks = abis.map { abi ->
    tasks.register<Exec>("buildNative-$abi") {
        val buildDir = outDir.resolve("cmake/$abi")
        val out = outDir.resolve("jniLibs/$abi/libadvancex.so")
        inputs.dir(rootProject.file("native")).withPropertyName("nativeSources")
        outputs.file(out)
        doFirst {
            buildDir.mkdirs()
            val configure = ProcessBuilder(
                cmakeExe, "-S", rootProject.file("native").absolutePath, "-B", buildDir.absolutePath, "-G", "Ninja",
                "-DCMAKE_TOOLCHAIN_FILE=${ndkDir}/build/cmake/android.toolchain.cmake",
                "-DANDROID_ABI=$abi", "-DANDROID_PLATFORM=android-$minSdk", "-DANDROID_STL=c++_static",
                "-DCMAKE_BUILD_TYPE=Release",
            ).inheritIO().start().waitFor()
            if (configure != 0) throw GradleException("CMake configure failed for $abi")
            out.parentFile.mkdirs()
        }
        commandLine(cmakeExe, "--build", buildDir.absolutePath, "--target", "advancex", "--parallel")
        doLast {
            val strip = File(ndkDir, "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip")
            val result = ProcessBuilder(strip.absolutePath, "--strip-unneeded", "-o", out.absolutePath,
                buildDir.resolve("libadvancex.so").absolutePath).inheritIO().start().waitFor()
            if (result != 0) throw GradleException("strip failed for $abi")
        }
    }
}

// --- Packaging + signing --------------------------------------------------------------------------
val unsignedApk = outDir.resolve("unsigned.apk")
val alignedApk = outDir.resolve("aligned.apk")
val finalApk = layout.buildDirectory.file("outputs/apk/AdvanceX-$versionName-debug.apk").get().asFile
val debugKeystore = outDir.resolve("debug.keystore")

val packageApk by tasks.registering {
    dependsOn(aapt2Link, dex, nativeTasks)
    inputs.files(linkedApk)
    inputs.dir(dexDir)
    inputs.dir(outDir.resolve("jniLibs"))
    outputs.file(unsignedApk)
    doLast {
        ZipOutputStream(unsignedApk.outputStream()).use { zip ->
            fun stored(name: String, bytes: ByteArray) {
                val e = ZipEntry(name)
                e.method = ZipEntry.STORED
                e.size = bytes.size.toLong()
                e.compressedSize = bytes.size.toLong()
                e.crc = CRC32().apply { update(bytes) }.value
                zip.putNextEntry(e)
                zip.write(bytes)
                zip.closeEntry()
            }
            ZipFile(linkedApk).use { src ->
                for (entry in src.entries()) {
                    val bytes = src.getInputStream(entry).readBytes()
                    if (entry.method == ZipEntry.STORED) stored(entry.name, bytes)
                    else {
                        zip.putNextEntry(ZipEntry(entry.name))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
            }
            dexDir.listFiles { f -> f.name.endsWith(".dex") }!!.sortedBy { it.name }.forEach { dexFile ->
                zip.putNextEntry(ZipEntry(dexFile.name))
                zip.write(dexFile.readBytes())
                zip.closeEntry()
            }
            // Native libraries stay uncompressed and page-aligned so they can be
            // mapped straight from the APK.
            outDir.resolve("jniLibs").listFiles()!!.sortedBy { it.name }.forEach { abiDir ->
                stored("lib/${abiDir.name}/libadvancex.so", abiDir.resolve("libadvancex.so").readBytes())
            }
        }
    }
}

val signApk by tasks.registering {
    dependsOn(packageApk)
    inputs.file(unsignedApk)
    outputs.file(finalApk)
    doLast {
        fun run(vararg cmd: String) {
            val code = ProcessBuilder(*cmd).inheritIO().start().waitFor()
            if (code != 0) throw GradleException("Command failed: ${cmd.joinToString(" ")}")
        }
        if (!debugKeystore.isFile) {
            run(
                "keytool", "-genkeypair", "-keystore", debugKeystore.absolutePath, "-storepass", "android",
                "-keypass", "android", "-alias", "androiddebugkey", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "10000", "-dname", "CN=AdvanceX Debug,O=AdvanceX,C=US",
            )
        }
        run(tool("zipalign"), "-P", "16", "-f", "4", unsignedApk.absolutePath, alignedApk.absolutePath)
        finalApk.parentFile.mkdirs()
        run(
            tool("apksigner"), "sign", "--ks", debugKeystore.absolutePath, "--ks-pass", "pass:android",
            "--key-pass", "pass:android", "--ks-key-alias", "androiddebugkey", "--min-sdk-version", minSdk,
            "--out", finalApk.absolutePath, alignedApk.absolutePath,
        )
        run(tool("apksigner"), "verify", "--min-sdk-version", minSdk, finalApk.absolutePath)
        logger.lifecycle("APK: ${finalApk.absolutePath} (${finalApk.length() / 1024} KiB)")
    }
}

tasks.register("assembleSdkDebug") {
    group = "build"
    description = "Builds a signed debug APK without the Android Gradle Plugin."
    dependsOn(signApk)
}
