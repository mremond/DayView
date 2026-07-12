import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)

    androidTarget()
    jvm()
    val xcf = XCFramework("DayViewKit")
    listOf(macosArm64(), macosX64()).forEach { target ->
        target.binaries.framework {
            baseName = "DayViewKit"
            xcf.add(this)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

android {
    namespace = "fr.dayview.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }
}

val syncXCFramework by tasks.registering(Sync::class) {
    dependsOn("assembleDayViewKitReleaseXCFramework")
    from(layout.buildDirectory.dir("XCFrameworks/release/DayViewKit.xcframework"))
    into(rootProject.layout.projectDirectory.dir("macos/Packages/DayViewKit/DayViewKit.xcframework"))
}
