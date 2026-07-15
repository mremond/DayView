# AGP 9 Module Split (`:composeApp` → `:shared` + `:androidApp`) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split `:composeApp` into a KMP library `:shared` + an Android application `:androidApp`, so `com.android.application` no longer coexists with the KMP plugin, and delete the AGP-9 bypass flags.

**Architecture:** Rename `:composeApp` → `:shared` and convert it to `com.android.library` (keeps all shared Compose UI, the sync subsystem, the desktop app + DMG/Linux packaging, and the Android-library `actual`s). A new `:androidApp` (`com.android.application`, non-KMP) holds the Android app components (`MainActivity`, `DayViewApplication`, `DayViewWidget`, services, alarms, notifications), manifest, `res/`, and app config, and depends on `:shared`.

**Tech Stack:** Kotlin Multiplatform 2.4.0, AGP 9.2.1, Compose Multiplatform, Gradle version catalog.

## Global Constraints

- Structural refactor ONLY — no behaviour change, no user-facing change.
- `:shared` = `com.android.library` + KMP + Compose (targets `androidTarget()` + `jvm("desktop")`). `:androidApp` = `com.android.application` + `org.jetbrains.kotlin.android` (NOT the KMP plugin) + Compose.
- Split rule: **manifest-declared component or app-level config → `:androidApp`; every `expect`/`actual` platform implementation (context-taking factory) → `:shared` `androidMain`.**
- Package stays `fr.dayview.app`; Android `namespace = "fr.dayview.app"`, `compileSdk = 37`, `minSdk = 24`, `targetSdk = 36`, `applicationId = "fr.dayview.app"`.
- Delete `android.builtInKotlin=false` and `android.newDsl=false` from `gradle.properties`.
- No AGP/Kotlin deprecation warning about `com.android.application` + KMP in build output.
- Keep the R8/security-crypto Tink `-dontwarn` in the app's proguard rules — local unit tests don't catch R8 failures; run `:androidApp:assembleRelease`.
- Commit messages: English, imperative, change-only; no Claude/Anthropic/AI references, no Co-Authored-By.
- Commit signing is disabled locally in this worktree; commits succeed unsigned.

## File / module map (end state)

- `settings.gradle.kts` — `include(":core", ":shared", ":androidApp")`.
- `build.gradle.kts` (root) — plugin aliases `apply false` (+ `kotlinAndroid`); shared app-version `extra` properties.
- `gradle/libs.versions.toml` — add `kotlinAndroid` plugin alias.
- `gradle.properties` — bypass flags removed.
- `shared/` (was `composeApp/`) — KMP library; `build.gradle.kts` becomes library config + keeps desktop packaging; `entitlements.plist`, `runtime-entitlements.plist`, `proguard-desktop.pro` stay.
- `androidApp/` — new: `build.gradle.kts`, `src/main/AndroidManifest.xml`, `src/main/kotlin/fr/dayview/app/…` (app components), `src/main/res/…`, `src/test/kotlin/…` (app-component tests), `proguard-rules.pro`.
- `.github/workflows/{ci,release}.yml`, `README.md`, `CLAUDE.md`, `.claude/launch.json` — updated task/paths.

---

## Task 1: Rename `:composeApp` → `:shared`

Pure rename with the module still an Android *application* (bypass flags still present), to isolate the rename churn from the structural split. Deliverable: the renamed module builds and tests green.

**Files:**
- Move: `composeApp/` → `shared/` (whole directory)
- Modify: `settings.gradle.kts`
- Modify: `shared/build.gradle.kts` (internal path strings)

- [ ] **Step 1: Move the module directory**

```bash
cd /Users/mremond/AIProjects/DayView/.claude/worktrees/dayview-zoom-integration-b405aa
git mv composeApp shared
```

- [ ] **Step 2: Update the settings include**

In `settings.gradle.kts`, change `include(":composeApp")` to `include(":shared")`.

- [ ] **Step 3: Fix internal path references in `shared/build.gradle.kts`**

The build script references two entitlements files by a `composeApp/…` path. Update both:

```kotlin
                entitlementsFile.set(
                    rootProject.layout.projectDirectory.file("shared/entitlements.plist"),
                )
                runtimeEntitlementsFile.set(
                    rootProject.layout.projectDirectory.file("shared/runtime-entitlements.plist"),
                )
```

(Also update the code comment on the `modules(...)` line that says `./gradlew :composeApp:run` → `:shared:run`. No other `composeApp` string remains in this file.)

- [ ] **Step 4: Verify the renamed module builds and tests**

Run: `./gradlew :shared:testDebugUnitTest :shared:desktopTest`
Expected: BUILD SUCCESSFUL (still `com.android.application` + bypass flags — unchanged behaviour, new name).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: rename :composeApp module to :shared"
```

---

## Task 2: Split `:shared` into a library + create `:androidApp`; remove bypass flags

Convert `:shared` to `com.android.library`, extract the Android app into `:androidApp`, and delete the flags. A module split is atomic — it can't compile until the whole split is coherent — so this task is executed by making the structural moves + config, then **iterating `./gradlew :androidApp:assembleDebug` and `:shared:compileDebugKotlinAndroid`**, resolving the fallout (mainly `internal` → `public` promotions of `:shared` symbols the app references, and moving any dependency the app needs). Deliverable: the full gate + release-build + DMG green, flags gone.

**Files:**
- Modify: `build.gradle.kts` (root) — shared version `extra`
- Modify: `gradle/libs.versions.toml` — `kotlinAndroid` alias
- Modify: `settings.gradle.kts` — add `:androidApp`
- Modify: `gradle.properties` — delete flags
- Modify: `shared/build.gradle.kts` — library plugin + trimmed `android {}` + version read
- Create: `androidApp/build.gradle.kts`
- Move: app components, manifest, `res/`, `proguard-rules.pro`, app-component tests → `androidApp/`
- Modify: `:shared` sources — promote app-referenced `internal` declarations to `public`

**Interfaces:**
- Produces: root `extra["appVersion"]: String`, `extra["appVersionCode"]: Int`, `extra["appPackageVersion"]: String`; module `:androidApp` (`com.android.application`, applicationId `fr.dayview.app`) depending on `:shared`.

- [ ] **Step 1: Add the `kotlinAndroid` plugin alias**

In `gradle/libs.versions.toml`, under `[plugins]`, add:

```toml
kotlinAndroid = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
```

- [ ] **Step 2: Hoist the app-version logic to the root, shared via `extra`**

In the root `build.gradle.kts`, above the `plugins {}` block, add:

```kotlin
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
```

Add `alias(libs.plugins.kotlinAndroid) apply false` to the root `plugins {}` block.

- [ ] **Step 3: Convert `shared/build.gradle.kts` to a library**

Make these edits in `shared/build.gradle.kts`:

1. Plugins: replace `alias(libs.plugins.androidApplication)` with `alias(libs.plugins.androidLibrary)`.
2. Delete the local `appVersion`, `deriveVersionCode`, `appVersionCode`, and `appPackageVersion` declarations (lines defining them). Where `appPackageVersion` is used (the `compose.desktop` `packageVersion`, `customizePackagedDmg`, `installMac`), read the root value once near the top:

```kotlin
val appPackageVersion: String = rootProject.extra["appPackageVersion"] as String
```

3. In the `android { }` block, remove the app-only config so only the library config remains:

```kotlin
android {
    namespace = "fr.dayview.app"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}
```

(Removes `defaultConfig { applicationId; targetSdk; versionCode; versionName }` and the entire `buildTypes { release { … } }` block — those move to `:androidApp`.)

- [ ] **Step 4: Create the `:androidApp` build script**

Create `androidApp/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
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

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
```

This is a **starting point** — Step 8's iteration adds any dependency the moved app components turn out to need (e.g. Glance for the widget, `androidx.core` for notifications). Add them from `libs.*` as compile errors reveal them; do not invent versions.

- [ ] **Step 5: Register `:androidApp` and delete the bypass flags**

In `settings.gradle.kts`: `include(":core")`, `include(":shared")`, `include(":androidApp")`.

In `gradle.properties`, delete the two lines `android.builtInKotlin=false` and `android.newDsl=false` (and the explanatory comment above them).

- [ ] **Step 6: Move the Android app components, manifest, resources, proguard**

```bash
mkdir -p androidApp/src/main/kotlin/fr/dayview/app androidApp/src/test/kotlin/fr/dayview/app
git mv shared/src/androidMain/AndroidManifest.xml androidApp/src/main/AndroidManifest.xml
git mv shared/src/androidMain/res androidApp/src/main/res
git mv shared/proguard-rules.pro androidApp/proguard-rules.pro
for f in MainActivity DayViewApplication DayViewWidget DayViewFocusTileService FocusAlarm FocusNotification PowerSettingsTarget; do
  git mv "shared/src/androidMain/kotlin/fr/dayview/app/$f.kt" "androidApp/src/main/kotlin/fr/dayview/app/$f.kt"
done
for t in DayViewApplicationTest DayViewWidgetTest DayViewWidgetRenderTest FocusAlarmTest FocusTileTest PowerSettingsTest; do
  git mv "shared/src/androidUnitTest/kotlin/fr/dayview/app/$t.kt" "androidApp/src/test/kotlin/fr/dayview/app/$t.kt"
done
```

Leave in `:shared` `androidMain`: every `*.android.kt` `actual`, `AndroidDataStore.kt`, `DayViewPreferences.kt`, and the tests `AndroidCompactLayoutTest.kt`, `AndroidDataStoreTest.kt`, `DayViewPreferencesTest.kt` (they test shared actuals/UI). If iteration shows a moved app component still lives in `androidMain` or a moved file references an `androidMain`-only symbol, apply the split rule to relocate the specific declaration.

- [ ] **Step 7: Promote app-referenced `:shared` symbols to public**

`MainActivity` calls `DayViewApp(...)`, which is `internal fun DayViewApp` in `shared/src/commonMain/kotlin/fr/dayview/app/App.kt`. Across a module boundary `internal` is invisible, so change `internal fun DayViewApp` to `fun DayViewApp` (public). Do the same for any other `:shared` declaration the moved app components reference (found in Step 8) — remove the `internal` modifier. Prefer promoting the exact referenced declarations over blanket changes.

- [ ] **Step 8: Iterate the build until `:androidApp` and `:shared` compile**

Run repeatedly, fixing the top errors each pass:

```bash
./gradlew :shared:compileDebugKotlinAndroid :androidApp:assembleDebug
```

Typical fixes, in order of likelihood: (a) an `internal` `:shared` symbol referenced by an app component → make it public (Step 7); (b) a dependency the app component needs → add it to `androidApp/build.gradle.kts` from `libs.*`; (c) a file that should have moved (or stayed) per the split rule → `git mv` it. Do NOT add Compose/androidx to `:core`, and do NOT re-introduce the bypass flags. Stop when both commands are BUILD SUCCESSFUL.

- [ ] **Step 9: Verify the full gate, release APK, and desktop DMG**

Run: `./gradlew ktlintCheck :core:jvmTest :shared:testDebugUnitTest :shared:desktopTest :androidApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :androidApp:assembleRelease`
Expected: BUILD SUCCESSFUL (R8; if it fails on security-crypto Tink errorprone annotations, ensure the moved `androidApp/proguard-rules.pro` keeps its `-dontwarn` entries).

Run: `./gradlew :shared:packageDmg` (macOS host) — expected BUILD SUCCESSFUL, DMG produced under `shared/build/compose/binaries/main-release/dmg/`. Also confirm `:core:compileKotlinMacosArm64` still passes (unaffected).

- [ ] **Step 10: Confirm the AGP deprecation warning is gone**

Run: `./gradlew :androidApp:assembleDebug --warning-mode all 2>&1 | grep -i "com.android.application" || echo "no KMP+application warning"`
Expected: `no KMP+application warning`. Also `grep -E 'builtInKotlin|newDsl' gradle.properties` returns nothing.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "build: split :shared library from :androidApp application and drop AGP-9 bypass flags"
```

---

## Task 3: Update CI, docs, and tooling references

Point every external `:composeApp` reference at the new module layout. Deliverable: CI workflows, README, CLAUDE.md, and launch config reference the split modules; the release/CI task graph resolves.

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/release.yml`
- Modify: `README.md`
- Modify: `CLAUDE.md`
- Modify: `.claude/launch.json` (if it references composeApp)

- [ ] **Step 1: Update `ci.yml`**

Replace the `:composeApp` references:
- `./gradlew :core:jvmTest :composeApp:testDebugUnitTest` → `./gradlew :core:jvmTest :shared:testAndroidHostTest :androidApp:testDebugUnitTest` (Task 2 migrated `:shared` to `com.android.kotlin.multiplatform.library`, whose Android unit-test task is `testAndroidHostTest`, not `testDebugUnitTest`)
- `./gradlew :composeApp:desktopTest` → `./gradlew :shared:desktopTest` (both occurrences)
- `path: composeApp/build/reports/visual-tests/*.png` → `path: shared/build/reports/visual-tests/*.png` (both occurrences)
- `./gradlew :composeApp:assembleDebug :composeApp:assembleRelease` → `./gradlew :androidApp:assembleDebug :androidApp:assembleRelease`

- [ ] **Step 2: Update `release.yml`**

- `./gradlew :composeApp:assembleRelease …` → `./gradlew :androidApp:assembleRelease …`
- `cp composeApp/build/outputs/apk/release/composeApp-release.apk …` → `cp androidApp/build/outputs/apk/release/androidApp-release.apk …`
- `./gradlew :composeApp:packageReleaseDistributionForCurrentOS …` → `./gradlew :shared:packageReleaseDistributionForCurrentOS …`
- `composeApp/build/compose/binaries/main-release/app/DayView …` → `shared/build/compose/binaries/main-release/app/DayView …`
- `find composeApp/build/compose/binaries/main-release …` → `find shared/build/compose/binaries/main-release …`
- `./gradlew :composeApp:packageReleaseDmg …` → `./gradlew :shared:packageReleaseDmg …`
- `cp composeApp/build/compose/binaries/main-release/dmg/DayView-*.dmg dist/` → `cp shared/build/compose/binaries/main-release/dmg/DayView-*.dmg dist/`

- [ ] **Step 3: Update `README.md`**

Change the build commands: `./gradlew :composeApp:run` → `./gradlew :shared:run`; `:composeApp:assembleDebug`/`installDebug`/`assembleDebug` (Android) → `:androidApp:*`; `:composeApp:packageDmg`/`installMac` → `:shared:*`; the generated-APK path `composeApp/build/outputs/apk/debug/composeApp-debug.apk` → `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

- [ ] **Step 4: Update `CLAUDE.md`**

In the Commands section: `./gradlew :composeApp:run` → `:shared:run`; `:composeApp:assembleDebug`/`installDebug` → `:androidApp:*`; `:composeApp:packageDmg` → `:shared:packageDmg`. In the test/lint gate line, replace `:composeApp:testDebugUnitTest :composeApp:desktopTest` with `:shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest` (`:shared`'s Android unit-test task is `testAndroidHostTest` after the KMP-library-plugin migration).

- [ ] **Step 5: Update `.claude/launch.json` if present**

If `.claude/launch.json` has a config whose `runtimeArgs` reference `:composeApp:run`, change it to `:shared:run`. (If the file is empty/absent, skip.)

- [ ] **Step 6: Sanity-check the task graph resolves**

Run: `./gradlew :androidApp:assembleRelease :shared:packageReleaseDmg --dry-run 2>&1 | tail -5`
Expected: the task graph resolves (no "task not found"). This confirms the release workflow's task names are valid post-split. (macOS host for the DMG task.)

- [ ] **Step 7: Commit**

```bash
git add .github/workflows/ci.yml .github/workflows/release.yml README.md CLAUDE.md .claude/launch.json
git commit -m "ci: point workflows and docs at the :shared / :androidApp split"
```

---

## Self-Review Notes

- **Spec coverage:** rename → Task 1; library/app split + flag removal + version hoist + compose resources (stay in `:shared`, consumed transitively) → Task 2; CI/docs/launch churn → Task 3. Done criteria (green gate, `assembleRelease`, `packageDmg`, no deprecation warning, flags gone) → Task 2 Steps 9–10 + Task 3 Step 6.
- **The two spec risks are addressed:** the release workflow is Task 3 (with a `--dry-run` graph check); the `androidUnitTest` split is Task 2 Step 6 (app-component tests move, actual/UI tests stay); the version-derivation-without-drift risk is solved by hoisting it to root `extra` (single source, Task 2 Step 2) rather than duplicating.
- **Module-split reality:** Task 2 is a large atomic task executed by iterating the compile (Step 8) — the visibility promotions (e.g. `DayViewApp` → public) and any app-only dependency additions surface at compile and are resolved there, mirroring how the earlier `:core` reconciliation was done. This is method, not a placeholder: the build configs, moves, and known promotions are all specified.
- **Type/name consistency:** `extra["appVersion"]`/`["appVersionCode"]`/`["appPackageVersion"]` are defined in root (Task 2 Step 2) and consumed in `:shared` (Step 3) and `:androidApp` (Step 4); module names `:shared`/`:androidApp` are consistent across settings, CI, and docs.
- **Placeholder scan:** no TBD/TODO; every config/edit shows exact content, and the one iterative step names the exact commands and fix categories.
