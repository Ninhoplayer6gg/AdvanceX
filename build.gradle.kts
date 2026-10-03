// SPDX-License-Identifier: MPL-2.0
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    // AGP and the Kotlin Gradle plugin must share a classloader, so AGP is put
    // on the root classpath here, but only for the standard (agp) build mode.
    val androidBuild = (project.findProperty("advancex.android.build") as String?) ?: "agp"
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        if (androidBuild == "agp") {
            classpath(libs.android.gradle.plugin)
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    group = "io.advancex"
    version = "0.1.0"
}

subprojects {
    // Bytecode level 17 everywhere: required by AGP, desugared by D8.
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(17)
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            allWarningsAsErrors.set(false)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Lets tests locate shared fixtures (e.g. the test cartridge).
        systemProperty("advancex.repoRoot", rootDir.absolutePath)
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
