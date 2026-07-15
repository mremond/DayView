# Split `:composeApp` into `:shared` (KMP library) + `:androidApp` (AGP 9 migration)

Closes issue #18.

## Context

PR #7 upgraded the Android Gradle Plugin to 9.x. AGP 9.0 **removed support for applying
`com.android.application` in the same module as the `org.jetbrains.kotlin.multiplatform`
plugin** — exactly how `:composeApp` is structured. The build currently survives on bypass
flags in `gradle.properties`:

```properties
android.builtInKotlin=false
android.newDsl=false
```

These restore the legacy DSL / built-in-Kotlin path, but the legacy variant API they rely on
is **scheduled for removal in AGP 10**. Every build emits deprecation warnings about the KMP +
`com.android.application` combination, and when AGP 10 lands (a Dependabot bump away), the
bypass can no longer fix it.

The `:core` module (Compose-free shared logic) already exists — it was the first increment of
this restructure. This spec completes it: split `:composeApp` into a Kotlin Multiplatform
**library** and a separate Android **application**, and remove the bypass flags.

This is a **structural refactor only**: no behaviour change, no user-facing change. The risk is
in getting the build/release wiring right, not in the app logic.

## Goals

- Move `com.android.application` out of any module that also applies the KMP plugin.
- Remove `android.builtInKotlin=false` / `android.newDsl=false`.
- No AGP/Kotlin deprecation warnings about the KMP + `com.android.application` combination.
- Full gate, both APK build types, and the desktop DMG all green; CI + docs updated.

## Non-Goals

- No new features, no behaviour changes.
- Not moving the sync subsystem (or any other logic) down into `:core` — it stays in `:shared`.
  (Noted as possible future work when native macOS sync arrives; out of scope here.)
- Not separating the desktop app into its own module — the desktop entry + packaging stay in
  `:shared` (the JVM target has no AGP-9 conflict).

## Decisions (from brainstorming)

1. **Topology:** one KMP **library** (`:shared`) holding common + desktop + Android-library
   `actual`s, plus one `com.android.application` module (`:androidApp`). (Option a.)
2. **Name:** the library is `:shared` — it holds shared Compose UI **and** the sync/crypto
   subsystem **and** the desktop app **and** Mac integration, so `:ui` would understate it.

## Target topology (3 modules)

- **`:core`** — unchanged. Compose-free shared logic; targets android/jvm/macosArm64/macosX64.
- **`:shared`** — renamed from `:composeApp`. A KMP **library**: `com.android.library` +
  `org.jetbrains.kotlin.multiplatform` + Compose. Targets `androidTarget()` + `jvm("desktop")`.
  Holds:
  - `commonMain` — shared Compose UI (screens/panels/theme) and the non-UI logic still here
    (the whole `sync/` subsystem, UI-state models, resource-backed formatting) + Compose
    resources (`composeResources`, generated `fr.dayview.app.generated.resources.Res`).
  - `desktopMain` — the desktop app entry (`Main.kt`), the Mac integration layer
    (`MacFocusStatusItem`, `MacLoginLauncher`, `MacRunningApplicationsProvider`, `MacDockBadge`,
    `MacFocusNudgeNotifier`, `FocusDriftDetector`, …), desktop `actual`s, and the
    `compose.desktop { application { … } }` block with DMG/Linux packaging.
  - `androidMain` — every `expect`/`actual` platform implementation (see split rule below).
- **`:androidApp`** — new. `com.android.application` + `org.jetbrains.kotlin.android` (NOT the
  KMP plugin) + Compose. `implementation(project(":shared"))`. Holds the Android app components
  + config.

## The split rule

**Manifest-declared component or app-level config → `:androidApp`. `expect`/`actual` platform
implementation (a context-taking factory) → `:shared` `androidMain`** (its `expect` is in
`:shared` `commonMain`, so the `actual` must live in the same module).

**Moves to `:androidApp`:**

- `MainActivity`, `DayViewApplication`, `DayViewWidget` (Glance), `DayViewFocusTileService`,
  `FocusAlarm` (+ its receiver), `FocusNotification`, `PowerSettingsTarget`.
- `AndroidManifest.xml`, Android `res/` (mipmaps / launcher icon / `xml/` / `values/`).
- App config: `applicationId`, `versionCode`/`versionName`, `buildTypes` (release: minify,
  shrink, proguard, debug-key signing), `proguard-rules.pro`.
- Android unit tests that exercise app components (e.g. `DayViewApplicationTest`,
  `DayViewPreferencesTest`) — the rest of `androidUnitTest` stays in `:shared`.

**Stays in `:shared` `androidMain`:** `androidDayPreferences`/`DayViewPreferences`,
`AndroidDataStore`, `CalendarSource.android`, `SoundCuePlayer.android`,
`DayHistoryStore.android`, the sync actuals (`SecureKeyStore.android`,
`SyncHttpClient.android`, `SyncOnResumeEffect.android`), `ClockPreference.android`,
`DisplayScaling.android`, `GoalPickerScale.android`, `TimePickerLauncher.android`,
`PlatformBackHandler.android`, `PlatformThemeChrome.android`, `RefreshClockOnResumeEffect.android`,
`Logging.android`, `SyncPairingPlatform.android`.

*(File-by-file assignment for anything ambiguous, especially a file that mixes a manifest
component with a context-taking helper, is resolved in the implementation plan; the rule above
governs.)*

## Context provisioning — a non-issue

The code already threads `Context` explicitly: `MainActivity` calls
`DayViewPreferences.get(applicationContext)`, `FocusAlarmScheduler(applicationContext)`,
`androidSecureKeyStore(applicationContext)`, `initCalendarSource(applicationContext)`, and
`androidDayPreferences(context: Context)` takes it as a parameter. So `:shared`'s Android
factories keep receiving context from `:androidApp` at their call sites — **no global-context
holder, `androidx.startup` Initializer, or ContentProvider is needed.**

## Build configuration

- **`:shared/build.gradle.kts`** — plugins: `kotlinMultiplatform`, `androidLibrary`,
  `jetbrainsCompose`, `composeCompiler`, `ktlint`. `kotlin { androidTarget(); jvm("desktop") }`
  with the existing source-set dependencies. `android { namespace = "fr.dayview.app";
  compileSdk = 37; defaultConfig { minSdk = 24 } }` — **library config only**, no
  `applicationId`/`versionCode`/`buildTypes`/signing. Keep the `compose.desktop { application
  { … } }` block and all desktop packaging tasks (`packageDmg`, `installMac`, the DMG
  customization, the Mac helper compile tasks) verbatim. Keep `compose.resources { … }`.
- **`:androidApp/build.gradle.kts`** — plugins: `androidApplication`,
  `org.jetbrains.kotlin.android` (add a `kotlinAndroid` alias to the version catalog),
  `jetbrainsCompose`, `composeCompiler`, `ktlint`. `android { … }` with `applicationId`,
  `versionCode`/`versionName` (derive from the shared app-version logic — move or share the
  `appVersion`/`deriveVersionCode` helpers), `buildTypes.release` (minify/shrink/proguard/debug
  signing), `testOptions`. `dependencies { implementation(project(":shared")); … }` plus the
  Android app-only deps (`activity-compose`, `ktor-client-okhttp`, Compose UI tooling, etc.).
- **`gradle.properties`** — delete `android.builtInKotlin=false` and `android.newDsl=false`.
- **`settings.gradle.kts`** — `include(":core", ":shared", ":androidApp")`.
- Root `build.gradle.kts` already declares the AGP/KMP/Compose plugin aliases `apply false`.

## Compose resources

Stay in `:shared` (`commonMain/composeResources`; generated `fr.dayview.app.generated.resources`).
`:androidApp` consumes them transitively — it references the same `Res` class through its
`:shared` dependency. Only the **Android launcher mipmaps / adaptive icon** move to
`:androidApp`'s `res/` (they're an app-packaging concern, not a Compose resource).

## Command / CI churn (from the rename + split)

- Desktop: `./gradlew :shared:run`, `:shared:packageDmg`, `:shared:installMac`.
- Android APK: `./gradlew :androidApp:assembleDebug`, `:androidApp:installDebug`,
  `:androidApp:assembleRelease`.
- Update: `.github/workflows/release.yml` (APK → `:androidApp`; DMG/Linux → `:shared`),
  `.claude/launch.json`, `README.md` (build commands), `CLAUDE.md` (the commands + the commit
  gate), and any `scripts/*` that reference `:composeApp`.

## Testing & done criteria

- **New gate:** `./gradlew ktlintCheck :core:jvmTest :shared:testDebugUnitTest
  :shared:desktopTest :androidApp:testDebugUnitTest` — green.
- `./gradlew :androidApp:assembleRelease` — green (R8; keep the security-crypto Tink
  `-dontwarn` in the app's proguard rules — local unit tests don't catch R8 failures).
- `./gradlew :shared:packageDmg` — desktop DMG builds; confirm the release workflow's Linux
  `.deb`/`.rpm`/AppImage tasks still resolve against `:shared`.
- **Build output has no AGP/Kotlin deprecation warning** about applying
  `com.android.application` with the Kotlin Multiplatform plugin.
- `grep` confirms `android.builtInKotlin` / `android.newDsl` are gone from `gradle.properties`.
- `:core` and the native macOS build (`:core:runMacNative`) are unaffected and still green.

## Risks

- **Release workflow** — the APK now comes from `:androidApp`, the desktop artifacts from
  `:shared`. Getting the two right (and Linux packaging) is the main risk; verify each target.
- **`androidUnitTest` split** — Robolectric tests mostly test shared behaviour and stay in
  `:shared`; only genuine app-component tests move. Misplacing one surfaces as a compile error.
- **App-version wiring** — `versionCode`/`versionName` derivation currently lives in
  `:composeApp`; it must move to (or be shared with) `:androidApp` without drifting.
