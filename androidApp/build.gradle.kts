import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import javax.inject.Inject

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ktlint)
}

// Compose Multiplatform's resources live in :shared, but CMP 1.11.1 can't copy them into an
// AGP-9 com.android.kotlin.multiplatform.library's assets, so without help they never reach
// this APK and the app crashes on the first stringResource() call. :shared produces the
// correctly laid-out assets into dayviewAndroidComposeResources (see shared/build.gradle.kts);
// this task stages them and the AGP variant API adds them as a generated assets directory,
// which wires the task dependency for every assets consumer (merge, lint, packaging).
abstract class BundleComposeResources : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun bundle() {
        fs.sync {
            from(source)
            into(outputDirectory)
        }
    }
}

androidComponents {
    onVariants { variant ->
        val bundle =
            tasks.register<BundleComposeResources>(
                "bundle${variant.name.replaceFirstChar { it.uppercase() }}ComposeResources",
            ) {
                source.from(
                    project(":shared").layout.buildDirectory.dir("dayviewAndroidComposeResources"),
                )
                dependsOn(":shared:copyAndroidMainComposeResourcesToAndroidAssets")
            }
        variant.sources.assets?.addGeneratedSourceDirectory(
            bundle,
            BundleComposeResources::outputDirectory,
        )
    }
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "fr.dayview.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "fr.dayview.app"
        minSdk = 24
        targetSdk = 36
        versionCode = rootProject.extra["appVersionCode"] as Int
        versionName = rootProject.extra["appVersion"] as String
    }

    buildTypes {
        release {
            // TEMPORARY: sign with the debug key so the release APK installs via
            // sideload from a GitHub Release. Replace with a real upload keystore before Play.
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

dependencies {
    implementation(project(":shared"))
    implementation(project(":core"))
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.components.resources)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    // MainActivity wires the sync stack from :shared: it names ktor's HttpClient
    // (createSyncHttpClient/HttpSyncTransport) and CryptographyRandom directly.
    implementation(libs.ktor.client.core)
    implementation(libs.cryptography.core)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

// Run each JVM test task's classes across multiple forked JVMs.
tasks.withType<Test>().configureEach {
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
}
