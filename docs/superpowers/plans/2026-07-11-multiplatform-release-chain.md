# Multiplatform Release Chain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On pushing a `v*` git tag, automatically build a minified macOS `.dmg`, Linux `.deb`/`.rpm`/`.AppImage`, and a signed Android `.apk`, and publish them all to a single GitHub Release.

**Architecture:** One GitHub Actions workflow triggered on tag push fans out into three per-OS build jobs (android + linux on `ubuntu-latest`, macos on `macos-latest`), each uploading its artifact; a final `release` job downloads all artifacts and creates the GitHub Release. The tag is the single source of truth for the version, threaded into Gradle via `-Pappversion`. Desktop builds use Compose's minified `Release` packaging tasks; the portable Linux `.AppImage` is assembled from Compose's app-image output by a helper script wrapping `appimagetool`.

**Tech Stack:** Kotlin Multiplatform + Compose Multiplatform, Gradle (Kotlin DSL), GitHub Actions, jpackage (via Compose), R8/ProGuard, appimagetool.

## Rebase reconciliation (read first)

This plan was rebased onto `origin/main`, which already merged `e9562aa "ci: add PR workflow and harden build tooling"`. That commit changed the baseline in ways this plan now assumes:

- `composeApp/build.gradle.kts` already defines `val appVersion = "1.0.0"`, and `android.defaultConfig.versionName` + `compose.desktop … packageVersion` already reference it.
- The Android `release` build type already exists with `isMinifyEnabled = true`, `isShrinkResources = true`, and `proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")` — but **no `signingConfig`**.
- `composeApp/proguard-rules.pro` already exists (Android R8 rules, currently no app-specific rules).
- The build uses `kotlin { jvmToolchain(21) }`; CI (`.github/workflows/ci.yml`) runs on **JDK 21 (Temurin)**.
- **ktlint** is applied (`libs.plugins.ktlint`) and enforced by `./gradlew ktlintCheck`. Every `.kt`/`.kts` edit must pass it.
- Existing CI (`ci.yml`) already builds `:composeApp:assembleRelease` (minified) on every PR, so Android minification is known-good.

## Global Constraints

- JDK **21** (Temurin) everywhere — matches `ci.yml` and `jvmToolchain(21)`.
- ktlint-clean: after any `.kt`/`.kts` edit, `./gradlew ktlintCheck` must pass (run `./gradlew ktlintFormat` to auto-fix).
- Compose **1.11.1**, Kotlin **2.3.20**, AGP **8.12.3** (pinned in `gradle/libs.versions.toml` — do not change).
- `packageName = "DayView"`, `applicationId = "fr.dayview.app"`, `mainClass = "fr.dayview.app.MainKt"`.
- Version source of truth: the git tag minus a leading `v` (`v1.2.0` → `1.2.0`), passed to Gradle as `-Pappversion=<version>`. Default when absent: `1.0.0`.
- Android `versionCode` = `major*10000 + minor*100 + patch`, floored at 1.
- Native packagers (jpackage: dmg/deb/rpm) get a strictly numeric `X.Y.Z` version, first component ≥ 1 (`appPackageVersion`).
- Publish target: **GitHub Releases only**. **No secrets** anywhere.
- Desktop (macOS + Linux) ships **minified `Release`** builds; never obfuscate (`obfuscate = false`) so crash traces stay readable.
- Android ships a **debug-signed** APK; keep the already-enabled minification/shrinking.

**Verification style:** this is build/CI configuration, so "tests" are Gradle/CLI invocations whose output is inspected. Where possible each task first observes the *current* (wrong) behavior, then makes it right.

**Working directory:** repo root `/Users/mremond/AIProjects/DayView/.claude/worktrees/release-chain-multiplatform-43c6ae` (git worktree; branch `claude/release-chain-multiplatform-43c6ae`, already rebased on `origin/main`).

---

### Task 1: Derive the app version from the `appversion` property

**Files:**
- Modify: `composeApp/build.gradle.kts` (the `val appVersion` declaration near the top; `versionCode` in `android.defaultConfig`; `packageVersion` in `compose.desktop`)

**Interfaces:**
- Produces: three top-level Gradle vals — `val appVersion: String` (raw, tag-derived or `1.0.0`), `val appVersionCode: Int`, `val appPackageVersion: String` (numeric, jpackage-valid). Also `val isMacHost: Boolean`. Tasks 3 and 4 consume `appPackageVersion` and `isMacHost`.

- [ ] **Step 1: Observe the version is hardcoded (red)**

Run: `./gradlew :composeApp:assembleRelease -Pappversion=9.9.9`
Then: `cat composeApp/build/outputs/apk/release/output-metadata.json`
Expected (current, wrong): `"versionName": "1.0.0"` and `"versionCode": 1` — `-Pappversion` is ignored. (APK is `composeApp-release-unsigned.apk` at this stage; signing is Task 2.)

- [ ] **Step 2: Replace the `appVersion` declaration**

In `composeApp/build.gradle.kts`, replace this block (the two comment lines + the val):

```kotlin
// Single source of truth for the app version: Android versionName,
// the desktop package version, and the DMG customization task all derive from it.
val appVersion = "1.0.0"
```

with:

```kotlin
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
```

- [ ] **Step 3: Use `appVersionCode` for the Android version code**

In `android { defaultConfig { ... } }`, replace:

```kotlin
        versionCode = 1
```

with:

```kotlin
        versionCode = appVersionCode
```

(Leave `versionName = appVersion` as-is.)

- [ ] **Step 4: Use `appPackageVersion` for desktop packaging**

In `compose.desktop { application { nativeDistributions { ... } } }`, replace:

```kotlin
            packageVersion = appVersion
```

with:

```kotlin
            packageVersion = appPackageVersion
```

- [ ] **Step 5: ktlint + verify the version flows through (green)**

Run: `./gradlew ktlintFormat ktlintCheck`
Expected: PASS (no violations).

Run: `./gradlew :composeApp:assembleRelease -Pappversion=9.9.9`
Then: `cat composeApp/build/outputs/apk/release/output-metadata.json`
Expected: `"versionName": "9.9.9"` and `"versionCode": 90909`.

Run: `./gradlew :composeApp:assembleRelease` (no property)
Then: `cat composeApp/build/outputs/apk/release/output-metadata.json`
Expected: `"versionName": "1.0.0"` and `"versionCode": 10000`.

- [ ] **Step 6: Commit**

```bash
git add composeApp/build.gradle.kts
git commit -m "build: derive app version from the appversion property"
```

---

### Task 2: Sign the Android release APK with the debug key

**Files:**
- Modify: `composeApp/build.gradle.kts` (the existing `android { buildTypes { release { ... } } }` block)

**Interfaces:**
- Consumes: the existing minified `release` build type.
- Produces: `assembleRelease` now emits a signed, installable `composeApp-release.apk`.

- [ ] **Step 1: Observe the release APK is unsigned (red)**

Run: `./gradlew :composeApp:assembleRelease -Pappversion=9.9.9`
Then: `ls composeApp/build/outputs/apk/release/`
Expected (current, wrong): the file is `composeApp-release-unsigned.apk`.

- [ ] **Step 2: Add a `signingConfig` to the existing `release` block**

In `android { buildTypes { release { ... } } }`, add the `signingConfig` line as the first statement inside `release { ... }` (keep the existing minify/shrink/proguard lines unchanged):

```kotlin
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
```

- [ ] **Step 3: ktlint + verify the APK is signed (green)**

Run: `./gradlew ktlintCheck`
Expected: PASS.

Run: `./gradlew :composeApp:assembleRelease -Pappversion=9.9.9`
Then: `ls composeApp/build/outputs/apk/release/`
Expected: the file is `composeApp-release.apk` (no `-unsigned` suffix).

Optional (if `apksigner` is on PATH): `"$ANDROID_HOME"/build-tools/*/apksigner verify --print-certs composeApp/build/outputs/apk/release/composeApp-release.apk` → prints the debug cert, exits 0.

- [ ] **Step 4: Commit**

```bash
git add composeApp/build.gradle.kts
git commit -m "build: sign Android release APK with the debug key (temporary)"
```

---

### Task 3: Enable minified desktop release builds + add Linux target formats

**Files:**
- Create: `composeApp/proguard-desktop.pro`
- Modify: `composeApp/build.gradle.kts` (`compose.desktop { application { ... } }`: add the ProGuard block, guard `outputBaseDir` to macOS, expand `targetFormats`)

**Interfaces:**
- Consumes: `appPackageVersion` and `isMacHost` (Task 1).
- Produces: desktop `Release` packaging tasks (`runRelease`, `packageReleaseDmg`, `packageReleaseDistributionForCurrentOS`) build a minified app; `targetFormats` includes `Deb` + `Rpm`; on non-macOS hosts, packaging output lands in the standard `composeApp/build/compose/binaries/` tree (needed by Tasks 5/6).

**Note:** a separate `proguard-desktop.pro` is used (not Android's `proguard-rules.pro`) to keep desktop-only keep rules — JNA, the desktop entry point, Compose resources — out of the Android config.

- [ ] **Step 1: Create the desktop ProGuard keep rules**

Create `composeApp/proguard-desktop.pro`:

```proguard
# Keep rules for the minified Compose Desktop (macOS/Linux) release build.

# Desktop entry point launched reflectively by the Compose/JVM launcher.
-keep class fr.dayview.app.MainKt { *; }

# JNA binds native code via reflection over these types.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }

# Our JNA Library interfaces (the Objective-C runtime bridges) are proxied by JNA.
-keep interface * extends com.sun.jna.Library { *; }

# Compose-generated resource accessors are reached by name.
-keep class fr.dayview.app.generated.resources.** { *; }

# Optional/desktop-only references R8 can't see through.
-dontwarn org.jetbrains.**
-dontwarn com.sun.jna.**
```

- [ ] **Step 2: Enable ProGuard and guard `outputBaseDir` (macOS-only)**

In `compose.desktop { application { ... } }`, add the ProGuard block right after `mainClass = "fr.dayview.app.MainKt"`:

```kotlin
        buildTypes.release.proguard {
            isEnabled.set(true)
            obfuscate.set(false)
            configurationFiles.from(project.file("proguard-desktop.pro"))
        }
```

Then wrap the existing `outputBaseDir.set(...)` call (inside `nativeDistributions { ... }`) in an `if (isMacHost)` guard. Replace:

```kotlin
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
```

with:

```kotlin
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
```

- [ ] **Step 3: Expand `targetFormats`**

Replace:

```kotlin
            targetFormats(TargetFormat.Dmg)
```

with:

```kotlin
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Rpm)
```

- [ ] **Step 4: ktlint + verify configuration**

Run: `./gradlew ktlintFormat ktlintCheck`
Expected: PASS.

Run: `./gradlew :composeApp:tasks --group "compose desktop" -Pappversion=9.9.9`
Expected: configuration succeeds and the list includes `packageReleaseDmg` and `packageReleaseDistributionForCurrentOS`.

- [ ] **Step 5: Minified launch smoke test (critical — macOS)**

Run: `./gradlew :composeApp:runRelease -Pappversion=9.9.9`
Expected: the DayView window opens and the menu-bar tray appears. Manually exercise: open/hide the window from the tray, and start a 25-minute Focus with an intention.
Watch the console for `ClassNotFoundException` / `NoSuchMethodError` / `MissingResourceException` — any means ProGuard stripped something. If so, add a matching `-keep` rule to `composeApp/proguard-desktop.pro` and re-run until it launches and a Focus session runs cleanly. Quit via the tray's "Quitter DayView".

- [ ] **Step 6: Commit**

```bash
git add composeApp/build.gradle.kts composeApp/proguard-desktop.pro
git commit -m "build: minify desktop release builds and add Linux deb/rpm formats"
```

---

### Task 4: Rewire the DMG customization to the release variant

**Files:**
- Modify: `composeApp/build.gradle.kts` (`customizePackagedDmg` / `copyPackagedDmg` definitions and the `tasks.matching { ... }` wiring)

**Interfaces:**
- Consumes: `appPackageVersion` (Task 1); the minified release packaging (Task 3).
- Produces: `packageReleaseDmg` yields a customized, versioned DMG at `composeApp/build/compose/binaries/main-release/dmg/DayView-<version>.dmg`.

**Background:** the release variant writes under `main-release/` (not `main/`), the customize task must key off `packageReleaseDmg` (not `packageDmg`), and the clean hook must also cover `createReleaseDistributable`. The DMG path currently uses `main/dmg/DayView-$appVersion.dmg`.

- [ ] **Step 1: Point the customize task at the release DMG path**

In the `customizePackagedDmg` task, replace:

```kotlin
    val packagedDmg = nativePackagingOutput.resolve("main/dmg/DayView-$appVersion.dmg")
```

with:

```kotlin
    val packagedDmg = nativePackagingOutput.resolve("main-release/dmg/DayView-$appPackageVersion.dmg")
```

- [ ] **Step 2: Point the copy task at the release path**

Replace the `copyPackagedDmg` task body:

```kotlin
val copyPackagedDmg by tasks.registering(Copy::class) {
    from(nativePackagingOutput.resolve("main/dmg"))
    into(layout.buildDirectory.dir("compose/binaries/main/dmg"))
}
```

with:

```kotlin
val copyPackagedDmg by tasks.registering(Copy::class) {
    from(nativePackagingOutput.resolve("main-release/dmg"))
    into(layout.buildDirectory.dir("compose/binaries/main-release/dmg"))
}
```

- [ ] **Step 3: Update the task-wiring matchers**

Replace:

```kotlin
tasks.matching { it.name == "createDistributable" }.configureEach {
    dependsOn(cleanNativePackagingOutput)
}
tasks.matching { it.name == "packageDmg" }.configureEach {
    finalizedBy(customizePackagedDmg)
}
```

with:

```kotlin
tasks.matching { it.name in setOf("createDistributable", "createReleaseDistributable") }.configureEach {
    dependsOn(cleanNativePackagingOutput)
}
tasks.matching { it.name == "packageReleaseDmg" }.configureEach {
    finalizedBy(customizePackagedDmg)
}
```

(Leave `customizePackagedDmg.configure { finalizedBy(copyPackagedDmg) }` unchanged.)

- [ ] **Step 4: ktlint + verify the customized release DMG (macOS)**

Run: `./gradlew ktlintCheck`
Expected: PASS.

Run: `./gradlew :composeApp:packageReleaseDmg -Pappversion=9.9.9`
Then: `ls composeApp/build/compose/binaries/main-release/dmg/`
Expected: `DayView-9.9.9.dmg` exists.
Optional: `hdiutil attach …/DayView-9.9.9.dmg` → confirm the volume shows DayView next to an `/Applications` shortcut, then `hdiutil detach` it.

- [ ] **Step 5: Commit**

```bash
git add composeApp/build.gradle.kts
git commit -m "build: package the customized DMG from the minified release variant"
```

---

### Task 5: Add the AppImage build script

**Files:**
- Create: `scripts/build_appimage.sh` (executable)

**Interfaces:**
- Consumes: Compose's release app-image directory `composeApp/build/compose/binaries/main-release/app/DayView` (produced by `packageReleaseDistributionForCurrentOS` on Linux), plus a PNG icon and a version.
- Produces: `<out-dir>/DayView-<version>.x86_64.AppImage`. Invoked by the Linux CI job (Task 6). Requires `appimagetool` on `PATH`.

- [ ] **Step 1: Create `scripts/build_appimage.sh`**

```bash
#!/usr/bin/env bash
set -euo pipefail

# Wrap a Compose/jpackage app-image directory into a portable AppImage.
# Usage: build_appimage.sh <app-image-dir> <version> <icon-png> <out-dir>

APP_IMAGE_DIR="${1:?app-image dir required}"
VERSION="${2:?version required}"
ICON_PNG="${3:?icon png required}"
OUT_DIR="${4:?output dir required}"

if [[ ! -x "${APP_IMAGE_DIR}/bin/DayView" ]]; then
    echo "error: ${APP_IMAGE_DIR}/bin/DayView not found or not executable" >&2
    exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
APPDIR="${WORK}/DayView.AppDir"
mkdir -p "${APPDIR}"
cp -a "${APP_IMAGE_DIR}/." "${APPDIR}/"

cat > "${APPDIR}/AppRun" <<'RUN'
#!/usr/bin/env bash
HERE="$(dirname "$(readlink -f "${0}")")"
exec "${HERE}/bin/DayView" "$@"
RUN
chmod +x "${APPDIR}/AppRun"

cat > "${APPDIR}/DayView.desktop" <<'DESKTOP'
[Desktop Entry]
Type=Application
Name=DayView
Exec=DayView
Icon=DayView
Categories=Utility;
Terminal=false
DESKTOP

cp "${ICON_PNG}" "${APPDIR}/DayView.png"

mkdir -p "${OUT_DIR}"
# The leading --appimage-extract-and-run makes the appimagetool AppImage run
# without FUSE (GitHub runners have no FUSE); remaining args go to the tool.
ARCH=x86_64 appimagetool --appimage-extract-and-run \
    "${APPDIR}" "${OUT_DIR}/DayView-${VERSION}.x86_64.AppImage"

echo "built ${OUT_DIR}/DayView-${VERSION}.x86_64.AppImage"
```

- [ ] **Step 2: Make it executable**

```bash
chmod +x scripts/build_appimage.sh
```

- [ ] **Step 3: Verify syntax + executable bit**

Run: `bash -n scripts/build_appimage.sh && test -x scripts/build_appimage.sh && echo OK`
Expected: prints `OK`. Full functional execution happens on Linux in the Task 6 dry run.

- [ ] **Step 4: Commit**

```bash
git add scripts/build_appimage.sh
git commit -m "build: add AppImage packaging script"
```

---

### Task 6: Add the tag-triggered GitHub Actions release workflow

**Files:**
- Create: `.github/workflows/release.yml`

**Interfaces:**
- Consumes: all Gradle wiring (Tasks 1–4) and `scripts/build_appimage.sh` (Task 5).
- Produces: a GitHub Release per pushed `v*` tag, with the APK, DMG, DEB, RPM, and AppImage attached.

- [ ] **Step 1: Create `.github/workflows/release.yml`**

```yaml
name: Release

on:
  push:
    tags:
      - 'v*'

permissions:
  contents: write

jobs:
  android:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: gradle/actions/wrapper-validation@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - uses: android-actions/setup-android@v3
      - uses: gradle/actions/setup-gradle@v4
      - name: Resolve version
        id: version
        run: echo "version=${GITHUB_REF_NAME#v}" >> "$GITHUB_OUTPUT"
      - name: Build release APK
        run: ./gradlew :composeApp:assembleRelease -Pappversion=${{ steps.version.outputs.version }}
      - name: Stage APK
        run: |
          mkdir -p dist
          cp composeApp/build/outputs/apk/release/composeApp-release.apk \
            "dist/DayView-${{ steps.version.outputs.version }}.apk"
      - uses: actions/upload-artifact@v4
        with:
          name: android
          path: dist/*.apk
          if-no-files-found: error

  linux:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: gradle/actions/wrapper-validation@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
      - name: Install packaging tools
        run: |
          sudo apt-get update
          sudo apt-get install -y rpm fakeroot binutils librsvg2-bin desktop-file-utils
      - name: Install appimagetool
        run: |
          curl -fsSL -o /tmp/appimagetool \
            https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage
          chmod +x /tmp/appimagetool
          sudo mv /tmp/appimagetool /usr/local/bin/appimagetool
      - name: Resolve version
        id: version
        run: echo "version=${GITHUB_REF_NAME#v}" >> "$GITHUB_OUTPUT"
      - name: Build Linux packages (.deb/.rpm + app-image)
        run: ./gradlew :composeApp:packageReleaseDistributionForCurrentOS -Pappversion=${{ steps.version.outputs.version }}
      - name: Render AppImage icon
        run: rsvg-convert -w 512 -h 512 artwork/dayview-icon-reference.svg -o /tmp/DayView.png
      - name: Build AppImage
        run: |
          mkdir -p dist
          scripts/build_appimage.sh \
            composeApp/build/compose/binaries/main-release/app/DayView \
            "${{ steps.version.outputs.version }}" \
            /tmp/DayView.png \
            dist
      - name: Stage deb/rpm
        run: |
          find composeApp/build/compose/binaries/main-release \
            -type f \( -name '*.deb' -o -name '*.rpm' \) -exec cp {} dist/ \;
          ls -la dist
      - uses: actions/upload-artifact@v4
        with:
          name: linux
          path: dist/*
          if-no-files-found: error

  macos:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v4
      - uses: gradle/actions/wrapper-validation@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
      - name: Resolve version
        id: version
        run: echo "version=${GITHUB_REF_NAME#v}" >> "$GITHUB_OUTPUT"
      - name: Build release DMG
        run: ./gradlew :composeApp:packageReleaseDmg -Pappversion=${{ steps.version.outputs.version }}
      - name: Stage DMG
        run: |
          mkdir -p dist
          cp composeApp/build/compose/binaries/main-release/dmg/DayView-*.dmg dist/
      - uses: actions/upload-artifact@v4
        with:
          name: macos
          path: dist/*.dmg
          if-no-files-found: error

  release:
    needs: [android, linux, macos]
    runs-on: ubuntu-latest
    steps:
      - uses: actions/download-artifact@v4
        with:
          path: artifacts
      - name: List artifacts
        run: find artifacts -type f
      - name: Publish GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          files: artifacts/**/*
          fail_on_unmatched_files: true
```

- [ ] **Step 2: Static-check the workflow (if `actionlint` is available)**

Run: `actionlint .github/workflows/release.yml`
Expected: no errors. (If `actionlint` isn't installed, skip — the dry run is the real check.)

- [ ] **Step 3: Commit and push the branch**

```bash
git add .github/workflows/release.yml
git commit -m "ci: add tag-triggered multiplatform release workflow"
git push -u origin claude/release-chain-multiplatform-43c6ae
```

- [ ] **Step 4: CI dry run with a throwaway tag** *(outward-facing — confirm with the user before running)*

Tag-triggered workflows run from the tagged commit's workflow file, so a test tag on this branch's HEAD works. Use a `v1.x` test tag (not a pre-1.0 tag): jpackage rejects major-0 versions, and `appPackageVersion` would remap any pre-1.0 tag to `1.0.0`, defeating the point of a distinct test artifact.

```bash
git tag v1.0.0-test
git push origin v1.0.0-test
gh run watch "$(gh run list --workflow=release.yml --limit 1 --json databaseId --jq '.[0].databaseId')"
```

Expected: all four jobs (android, linux, macos, release) succeed. Then:

```bash
gh release view v1.0.0-test --json assets --jq '.assets[].name'
```

Expected asset names (tag version `1.0.0-test`, jpackage version `1.0.0`):
- `DayView-1.0.0-test.apk`
- `DayView-1.0.0.dmg`
- `DayView-1.0.0-test.x86_64.AppImage`
- a `.deb` (e.g. `dayview_1.0.0_amd64.deb`)
- a `.rpm` (e.g. `dayview-1.0.0-1.x86_64.rpm`)

> Note: `.dmg`/`.deb`/`.rpm` carry the numeric jpackage version (`1.0.0`); `.apk` and `.AppImage` carry the raw tag string (`1.0.0-test`). Expected, given jpackage's numeric-only constraint. Confirm exact `.deb`/`.rpm` names from the listing.

- [ ] **Step 5: Clean up the test tag and release**

```bash
gh release delete v1.0.0-test --yes
git push origin --delete v1.0.0-test
git tag -d v1.0.0-test
```

- [ ] **Step 6: Manual Linux launch smoke test (not automatable from macOS)**

On a Linux machine/VM, capture the AppImage from the dry run before cleanup (cleanup below deletes `v1.0.0-test`), then:

```bash
chmod +x DayView-*.AppImage
./DayView-*.AppImage
```

Expected: the window opens; macOS-only features (menu-bar status item, login launcher, focus-drift detection) are inactive; no crash from `Native.load`.

---

## Self-Review

**1. Spec coverage:**
- GitHub Actions on tag + four jobs + Releases-only publish + no secrets → Task 6. ✓
- JDK 21 + ktlint-clean edits → Global Constraints + every Gradle task's Step. ✓
- Version from tag; versionName/versionCode/packageVersion; `1.0.0` default; numeric package version → Task 1. ✓
- Android debug signing on the existing minified release block → Task 2. ✓
- Desktop minification (ProGuard enabled + keep rules, `obfuscate=false`) → Task 3. ✓
- `targetFormats` Dmg/Deb/Rpm; `outputBaseDir` macOS-only → Task 3. ✓
- DMG release-variant path fix + clean hook for `createReleaseDistributable` → Task 4. ✓
- Linux `.deb`/`.rpm` via Compose + `.AppImage` via `scripts/build_appimage.sh` + `rsvg-convert` icon → Tasks 5, 6. ✓
- Linux `Native.load` runtime risk → verified already guarded (spec), covered by Task 6 Step 6. ✓
- Minified launch smoke test → Task 3 Step 5 (macOS) + Task 6 Step 6 (Linux). ✓

**2. Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to Task N" — every step carries concrete code or commands. ✓

**3. Type consistency:** `appVersion` / `appVersionCode` / `appPackageVersion` / `isMacHost` defined once (Task 1 Step 2), referenced consistently in Tasks 1–4. Desktop ProGuard file `proguard-desktop.pro` named consistently across Task 3 Steps 1/2/6. Task names `packageReleaseDmg`, `createReleaseDistributable`, `packageReleaseDistributionForCurrentOS`, `runRelease` consistent. App-image path `main-release/app/DayView` matches `packageName = "DayView"`. ✓

**Known residual uncertainties (verified in the Task 6 dry run):** exact jpackage `.deb`/`.rpm` filenames, and that `packageReleaseDistributionForCurrentOS` emits the app-image at the assumed Linux path. The `find`-based staging and the dry run surface these without guesswork.
