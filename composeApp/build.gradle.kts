import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kotlinSerialization)
}

// Single source of truth for the app version: Android versionName/versionCode,
// the desktop package version, and the DMG customization task all derive from it.
// Overridable at release time via -Pappversion=<tag> (see .github/workflows/release.yml).
val appVersion: String =
    (findProperty("appversion") as String?)?.takeIf { it.isNotBlank() } ?: "1.0.0"

// Android requires a monotonic integer; derive it from the semver core.
fun deriveVersionCode(version: String): Int {
    val core = version.substringBefore('-').substringBefore('+')
    val parts = core.split('.')
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    return (major * 10_000 + minor * 100 + patch).coerceAtLeast(1)
}
val appVersionCode: Int = deriveVersionCode(appVersion)

// jpackage (dmg/deb/rpm) requires a strictly numeric X.Y.Z whose first component
// is >= 1; map any pre-release suffix or major-0 version to a valid numeric version.
val appPackageVersion: String =
    appVersion.substringBefore('-').substringBefore('+').let { core ->
        val major = core.substringBefore('.').toIntOrNull() ?: 0
        if (major < 1) "1.0.0" else core
    }

val isMacHost: Boolean =
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

// macOS dev signing: reuse a stable code identity so the calendar (TCC) grant
// survives rebuilds. Read from local.properties (per-machine, gitignored). When
// unset, the EventKit helper is left unsigned and behaviour is unchanged.
val macosSigningIdentity: String? =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }.getProperty("dayview.macos.signingIdentity")?.takeIf { it.isNotBlank() }

ktlint {
    filter {
        exclude { it.file.path.contains("/build/generated/") }
    }
}

kotlin {
    // Robolectric requires Java 21 to emulate the current compile SDK.
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
                implementation(libs.androidx.datastore.preferences.core)
                implementation(libs.jetbrains.lifecycle.runtime.compose)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
                implementation(libs.cryptography.core)
                implementation(libs.cryptography.provider.jdk)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.okio.fakefilesystem)
                implementation(libs.ktor.client.mock)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.compose.ui.tooling.preview)
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.datastore.preferences)
                implementation(libs.ktor.client.okhttp)
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation(libs.robolectric)
                implementation(libs.androidx.test.core)
            }
        }
        val desktopMain by getting {
            resources.srcDir(layout.buildDirectory.dir("generated/macosFocusStatusHelper"))
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.jna)
                implementation(libs.ktor.client.java)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.uiTestJUnit4)
            }
        }
    }
}

compose.resources {
    packageOfResClass = "fr.dayview.app.generated.resources"
}

android {
    namespace = "fr.dayview.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "fr.dayview.app"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
    }

    buildTypes {
        release {
            // TEMPORARY: sign with the debug key so the release APK installs via
            // sideload from a GitHub Release. Replace with a real upload keystore
            // (GitHub secrets) before any Play distribution.
            signingConfig = signingConfigs.getByName("debug")
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

        buildTypes.release.proguard {
            isEnabled.set(true)
            obfuscate.set(false)
            configurationFiles.from(project.file("proguard-desktop.pro"))
        }

        nativeDistributions {
            // Building an app bundle inside an iCloud/FileProvider-backed Documents
            // folder makes macOS attach com.apple.FinderInfo while jpackage is still
            // running. codesign rejects that attribute, so package in a local temp
            // directory and copy only the completed DMG back into build/. This only
            // applies to macOS; on Linux it would push .deb/.rpm/app-image into $TMPDIR.
            if (isMacHost) {
                outputBaseDir.set(
                    layout.dir(
                        providers.provider {
                            file("${System.getProperty("java.io.tmpdir")}/dayview-compose-package")
                        },
                    ),
                )
            }
            // DataStore's bundled protobuf serializer reaches for sun.misc.Unsafe at
            // runtime. jlink's module auto-detection can't see that reflective access,
            // so the trimmed runtime image ships without jdk.unsupported and the
            // packaged app crashes on the first preferences write (NoClassDefFoundError:
            // sun/misc/Unsafe). Pull the module in explicitly.
            modules("jdk.unsupported")
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "DayView"
            packageVersion = appPackageVersion
            description = "A visual representation of the time left in your day."
            vendor = "DayView"
            macOS {
                bundleID = "fr.dayview.app"
                iconFile.set(rootProject.layout.projectDirectory.file("artwork/dayview.icns"))
                // DayView spawns the EventKit helper as a child process, so macOS attributes
                // the calendar request to this app bundle. Without the usage description the
                // system denies access silently (no prompt). Keep it in sync with
                // scripts/MacEventKitHelper-Info.plist.
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSCalendarsFullAccessUsageDescription</key>
                        <string>DayView reads your calendar in read-only mode to subtract busy slots from the time you have left today.</string>
                        <key>NSCalendarsUsageDescription</key>
                        <string>DayView reads your calendar in read-only mode to subtract busy slots from the time you have left today.</string>
                    """.trimIndent()
                }
            }
        }
    }
}

val nativePackagingOutput = file("${System.getProperty("java.io.tmpdir")}/dayview-compose-package")
val cleanNativePackagingOutput by tasks.registering(Delete::class) {
    delete(nativePackagingOutput)
}
val customizePackagedDmg by tasks.registering(Exec::class) {
    val packagedDmg = nativePackagingOutput.resolve("main-release/dmg/DayView-$appPackageVersion.dmg")
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
    from(nativePackagingOutput.resolve("main-release/dmg"))
    into(layout.buildDirectory.dir("compose/binaries/main-release/dmg"))
}

// Local install: build the DMG, then copy DayView.app into /Applications, replacing
// any previous copy. Handy for testing the exact packaged artifact on this machine.
val installMac by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Package the DMG and install DayView.app into /Applications (macOS only)."
    dependsOn("packageDmg")
    onlyIf { System.getProperty("os.name").startsWith("Mac", ignoreCase = true) }
    val packagedDmg = nativePackagingOutput.resolve("main/dmg/DayView-$appPackageVersion.dmg")
    commandLine(
        rootProject.file("scripts/install_macos_local.sh").absolutePath,
        packagedDmg.absolutePath,
    )
}

tasks.matching { it.name in setOf("createDistributable", "createReleaseDistributable") }.configureEach {
    dependsOn(cleanNativePackagingOutput)
}
tasks.matching { it.name == "packageReleaseDmg" }.configureEach {
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

val macEventKitHelperUnsignedOutput =
    layout.buildDirectory.file("generated/macosEventKitHelperUnsigned/macos-eventkit-helper")
val macEventKitHelperUnsignedFile = macEventKitHelperUnsignedOutput.get().asFile
macEventKitHelperUnsignedFile.parentFile.mkdirs()
val compileMacEventKitHelper by tasks.registering(Exec::class) {
    onlyIf { System.getProperty("os.name").startsWith("Mac", ignoreCase = true) }
    inputs.file(rootProject.file("scripts/MacEventKitHelper.swift"))
    // Embedding the usage-description plist changes the binary, so re-run when it changes.
    inputs.file(rootProject.file("scripts/MacEventKitHelper-Info.plist"))
    outputs.file(macEventKitHelperUnsignedOutput)
    commandLine(
        "xcrun",
        "swiftc",
        rootProject.file("scripts/MacEventKitHelper.swift").absolutePath,
        "-O",
        "-framework",
        "EventKit",
        // macOS 14+ refuses calendar access unless the requesting binary carries an
        // NSCalendarsFullAccessUsageDescription. Embed an __info_plist section so the
        // helper (extracted and run outside any app bundle) can trigger the TCC prompt.
        "-Xlinker", "-sectcreate",
        "-Xlinker", "__TEXT",
        "-Xlinker", "__info_plist",
        "-Xlinker", rootProject.file("scripts/MacEventKitHelper-Info.plist").absolutePath,
        "-o",
        macEventKitHelperUnsignedFile.absolutePath,
    )
}

val macEventKitHelperResourceOutput =
    layout.buildDirectory.file("generated/macosFocusStatusHelper/macos-eventkit-helper")

abstract class PrepareEventKitHelper : DefaultTask() {
    @get:org.gradle.api.tasks.InputFile
    abstract val unsignedHelper: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.Input
    @get:org.gradle.api.tasks.Optional
    abstract val signingIdentity: org.gradle.api.provider.Property<String>

    @get:org.gradle.api.tasks.OutputFile
    abstract val signedHelper: org.gradle.api.file.RegularFileProperty

    @get:javax.inject.Inject
    abstract val execOps: org.gradle.process.ExecOperations

    @org.gradle.api.tasks.TaskAction
    fun prepare() {
        val source = unsignedHelper.get().asFile
        val target = signedHelper.get().asFile
        target.parentFile.mkdirs()
        source.copyTo(target, overwrite = true)
        target.setExecutable(true, false)
        val identity = signingIdentity.orNull
        if (!identity.isNullOrBlank()) {
            execOps.exec {
                commandLine(
                    "codesign",
                    "--force",
                    "--options", "runtime",
                    "--identifier", "fr.dayview.app.eventkit-helper",
                    "--sign", identity,
                    target.absolutePath,
                )
            }
        }
    }
}

val prepareMacEventKitHelper by tasks.registering(PrepareEventKitHelper::class) {
    onlyIf { System.getProperty("os.name").startsWith("Mac", ignoreCase = true) }
    dependsOn(compileMacEventKitHelper)
    unsignedHelper.set(macEventKitHelperUnsignedOutput)
    signedHelper.set(macEventKitHelperResourceOutput)
    if (macosSigningIdentity != null) {
        signingIdentity.set(macosSigningIdentity)
    } else if (isMacHost) {
        logger.lifecycle(
            "DayView: dayview.macos.signingIdentity is unset; " +
                "EventKit helper left unsigned (calendar permission will reset on rebuild).",
        )
    }
}

tasks.matching { it.name in setOf("desktopProcessResources", "desktopTestProcessResources") }.configureEach {
    dependsOn(compileMacFocusStatusHelper)
    dependsOn(prepareMacEventKitHelper)
}
