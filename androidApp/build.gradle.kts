plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ktlint)
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
