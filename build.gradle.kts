// Single source of truth for the app version, shared by :androidApp (versionName/versionCode)
// and :shared (desktop packageVersion). Overridable at release time via -Pappversion=<tag>.
val appVersion: String = (findProperty("appversion") as String?)?.takeIf { it.isNotBlank() } ?: "1.0.0"

fun deriveVersionCode(version: String): Int {
    val core = version.substringBefore('-').substringBefore('+')
    val parts = core.split('.')
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    return (major * 10_000 + minor * 100 + patch).coerceAtLeast(1)
}

// jpackage requires a strictly numeric X.Y.Z with first component >= 1.
val appPackageVersion: String = appVersion.substringBefore('-').substringBefore('+').let { core ->
    val major = core.substringBefore('.').toIntOrNull() ?: 0
    if (major < 1) "1.0.0" else core
}

extra["appVersion"] = appVersion
extra["appVersionCode"] = deriveVersionCode(appVersion)
extra["appPackageVersion"] = appPackageVersion

plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidKmpLibrary) apply false
    alias(libs.plugins.jetbrainsCompose) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.ktlint) apply false
}
