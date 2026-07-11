import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ktlint)
}

// Single source of truth for the app version: Android versionName,
// the desktop package version, and the DMG customization task all derive from it.
val appVersion = "1.0.0"

ktlint {
    filter {
        exclude { it.file.path.contains("/build/generated/") }
    }
}

kotlin {
    // Robolectric requires Java 21 to emulate compileSdk 36.
    jvmToolchain(21)

    androidTarget()
    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.compose.ui)
                implementation(libs.compose.components.resources)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.compose.ui.tooling.preview)
                implementation(libs.androidx.activity.compose)
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation(libs.robolectric)
            }
        }
        val desktopMain by getting {
            resources.srcDir(layout.buildDirectory.dir("generated/macosFocusStatusHelper"))
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.jna)
            }
        }
    }
}

compose.resources {
    packageOfResClass = "fr.dayview.app.generated.resources"
}

android {
    namespace = "fr.dayview.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.dayview.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = appVersion
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

compose.desktop {
    application {
        mainClass = "fr.dayview.app.MainKt"

        nativeDistributions {
            // Building an app bundle inside an iCloud/FileProvider-backed Documents
            // folder makes macOS attach com.apple.FinderInfo while jpackage is still
            // running. codesign rejects that attribute, so package in a local temp
            // directory and copy only the completed DMG back into build/.
            outputBaseDir.set(
                layout.dir(
                    providers.provider {
                        file("${System.getProperty("java.io.tmpdir")}/dayview-compose-package")
                    },
                ),
            )
            targetFormats(TargetFormat.Dmg)
            packageName = "DayView"
            packageVersion = appVersion
            description = "Une représentation visuelle du temps qu'il reste aujourd'hui."
            vendor = "DayView"
            macOS {
                bundleID = "fr.dayview.app"
                iconFile.set(rootProject.layout.projectDirectory.file("artwork/dayview.icns"))
            }
        }
    }
}

val nativePackagingOutput = file("${System.getProperty("java.io.tmpdir")}/dayview-compose-package")
val cleanNativePackagingOutput by tasks.registering(Delete::class) {
    delete(nativePackagingOutput)
}
val customizePackagedDmg by tasks.registering(Exec::class) {
    val packagedDmg = nativePackagingOutput.resolve("main/dmg/DayView-$appVersion.dmg")
    val volumeIcon = rootProject.file("artwork/dayview.icns")

    onlyIf {
        System.getProperty("os.name").startsWith("Mac", ignoreCase = true) && packagedDmg.isFile
    }
    inputs.file(packagedDmg)
    inputs.file(volumeIcon)
    outputs.file(packagedDmg)
    commandLine(
        rootProject.file("scripts/customize_macos_dmg.sh").absolutePath,
        packagedDmg.absolutePath,
        volumeIcon.absolutePath,
    )
}
val copyPackagedDmg by tasks.registering(Copy::class) {
    from(nativePackagingOutput.resolve("main/dmg"))
    into(layout.buildDirectory.dir("compose/binaries/main/dmg"))
}

tasks.matching { it.name == "createDistributable" }.configureEach {
    dependsOn(cleanNativePackagingOutput)
}
tasks.matching { it.name == "packageDmg" }.configureEach {
    finalizedBy(customizePackagedDmg)
}
customizePackagedDmg.configure { finalizedBy(copyPackagedDmg) }

val macFocusHelperOutput = layout.buildDirectory.file("generated/macosFocusStatusHelper/macos-focus-status-helper")
val compileMacFocusStatusHelper by tasks.registering(Exec::class) {
    // Local File value so the doFirst lambda stays serializable for the configuration cache.
    val helperFile = macFocusHelperOutput.get().asFile
    onlyIf { System.getProperty("os.name").startsWith("Mac", ignoreCase = true) }
    inputs.file(rootProject.file("scripts/MacFocusStatusHelper.swift"))
    outputs.file(macFocusHelperOutput)
    doFirst { helperFile.parentFile.mkdirs() }
    commandLine(
        "xcrun",
        "swiftc",
        rootProject.file("scripts/MacFocusStatusHelper.swift").absolutePath,
        "-O",
        "-framework",
        "AppKit",
        "-o",
        helperFile.absolutePath,
    )
}

tasks.matching { it.name in setOf("desktopProcessResources", "desktopTestProcessResources") }.configureEach {
    dependsOn(compileMacFocusStatusHelper)
}
