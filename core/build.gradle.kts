import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.ktlint)
}

kotlin {
    applyDefaultHierarchyTemplate()
    jvmToolchain(21)

    android {
        namespace = "fr.dayview.core"
        compileSdk = 37
        minSdk = 24
    }
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
                implementation(libs.androidx.datastore.preferences.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val macosMain by getting { }
        // okio-fakefilesystem is JVM-only here: adding it to the native test binary
        // triggers a Kotlin/Native IR linker crash (duplicate kotlinx.datetime.Clock
        // typealias binding). The DataStore round-trip test runs on JVM; the store's
        // native support is proven by compileKotlinMacosArm64.
        val jvmTest by getting {
            dependencies {
                implementation(libs.okio.fakefilesystem)
            }
        }
    }
}

// Run each JVM test task's classes across multiple forked JVMs.
tasks.withType<Test>().configureEach {
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
}

val syncXCFramework by tasks.registering(Sync::class) {
    dependsOn("assembleDayViewKitReleaseXCFramework")
    from(layout.buildDirectory.dir("XCFrameworks/release/DayViewKit.xcframework"))
    into(rootProject.layout.projectDirectory.dir("macos/Packages/DayViewKit/DayViewKit.xcframework"))
}

// One-command build+run for the native SwiftUI macOS app: assemble and sync the
// XCFramework, generate the Xcode project (XcodeGen), build it, and launch the app.
val runMacNative by tasks.registering(Exec::class) {
    group = "application"
    description = "Build and launch the native SwiftUI macOS app (macOS only; needs Xcode + xcodegen)."
    dependsOn(syncXCFramework)
    onlyIf { System.getProperty("os.name").startsWith("Mac", ignoreCase = true) }
    commandLine(rootProject.file("scripts/run_macos_native.sh").absolutePath)
}
