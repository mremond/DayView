# Multiplatform Release Chain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On pushing a `v*` git tag, automatically build minified macOS `.dmg`, Linux `.deb`/`.rpm`/`.AppImage`, and an Android `.apk`, and publish them all to a single GitHub Release.

**Architecture:** One GitHub Actions workflow triggered on tag push fans out into three per-OS build jobs (android + linux on `ubuntu-latest`, macos on `macos-latest`), each uploading its artifact; a final `release` job downloads all artifacts and creates the GitHub Release. The tag is the single source of truth for the version, threaded into Gradle via `-Pappversion`. Desktop builds use Compose's minified `Release` packaging tasks; the portable Linux `.AppImage` is assembled from Compose's app-image output by a helper script wrapping `appimagetool`.

**Tech Stack:** Kotlin Multiplatform + Compose Multiplatform, Gradle (Kotlin DSL), GitHub Actions, jpackage (via Compose), R8/ProGuard, appimagetool.

## Global Constraints

- JDK **17** (Temurin) everywhere.
- Compose **1.11.1**, Kotlin **2.3.20**, AGP **8.12.3** (already pinned in `gradle/libs.versions.toml` — do not change).
- `packageName = "DayView"`, `applicationId = "fr.dayview.app"`, `mainClass = "fr.dayview.app.MainKt"`.
- Version source of truth: the git tag, minus a leading `v` (`v1.2.0` → `1.2.0`), passed to Gradle as `-Pappversion=<version>`.
- Android `versionCode` = `major*10000 + minor*100 + patch`, floored at 1.
- Local builds without `-Pappversion` fall back to version name `0.0.0-dev`; native packagers get `1.0.0` (jpackage requires a numeric version whose first component ≥ 1).
- Publish target: **GitHub Releases only**. No store upload. **No secrets** anywhere.
- Desktop (macOS + Linux) ships **minified `Release`** builds. Android ships a **debug-signed, non-minified** APK (temporary until a real upload keystore exists).
- Never obfuscate the desktop ProGuard build (`obfuscate = false`) to keep crash traces readable.

**Note on verification style:** this is build/CI configuration, so "tests" are Gradle/CLI invocations whose output is inspected, not unit tests. Where possible each task first runs a command to observe the *current* (wrong) behavior, then makes it right — a red/green cycle.

**Working directory:** repo root `/Users/mremond/AIProjects/DayView/.claude/worktrees/release-chain-multiplatform-43c6ae` (a git worktree; branch `claude/release-chain-multiplatform-43c6ae`).

---

### Task 1: Thread the tag version through Gradle

**Files:**
- Modify: `composeApp/build.gradle.kts` (add version derivation near the top; use it in the `android` and `compose.desktop` blocks)

**Interfaces:**
- Produces: three top-level Gradle vals available to the rest of the build script — `val appVersionName: String`, `val appVersionCode: Int`, `val appPackageVersion: String`. Later tasks (DMG path, Android signing) rely on `appPackageVersion` and the `android`/`compose.desktop` wiring done here.

- [ ] **Step 1: Observe the version is currently hardcoded (red)**

Run: `./gradlew :composeApp:assembleRelease -Pappversion=9.9.9`
Then: `cat composeApp/build/outputs/apk/release/output-metadata.json`
Expected (current, wrong): JSON shows `"versionName": "1.0"` and `"versionCode": 1` — the `-Pappversion` flag is ignored. (The APK will be `composeApp-release-unsigned.apk` at this stage; that's fine, signing is Task 2.)

- [ ] **Step 2: Add version derivation to the top of `composeApp/build.gradle.kts`**

Insert this block immediately after the `plugins { ... }` block (before `kotlin { ... }`):

```kotlin
val appVersionName: String =
    (findProperty("appversion") as String?)?.takeIf { it.isNotBlank() } ?: "0.0.0-dev"

fun deriveVersionCode(version: String): Int {
    val core = version.substringBefore('-').substringBefore('+')
    val parts = core.split('.')
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    return (major * 10_000 + minor * 100 + patch).coerceAtLeast(1)
}
val appVersionCode: Int = deriveVersionCode(appVersionName)

// jpackage (used for dmg/deb/rpm) requires a strictly numeric version whose
// first component is >= 1. Map the dev fallback (and any 0.0.0 core) to 1.0.0.
val appPackageVersion: String =
    appVersionName.substringBefore('-').substringBefore('+')
        .let { core -> if (core.isBlank() || core == "0.0.0") "1.0.0" else core }

val isMacHost: Boolean =
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
```

(`isMacHost` is defined here because Task 3 also needs it.)

- [ ] **Step 3: Use the derived version in the `android { defaultConfig { ... } }` block**

Replace:

```kotlin
        versionCode = 1
        versionName = "1.0"
```

with:

```kotlin
        versionCode = appVersionCode
        versionName = appVersionName
```

- [ ] **Step 4: Use the derived version for Compose packaging**

In the `compose.desktop { application { nativeDistributions { ... } } }` block, replace:

```kotlin
            packageVersion = "1.0.0"
```

with:

```kotlin
            packageVersion = appPackageVersion
```

- [ ] **Step 5: Verify the version now flows through (green)**

Run: `./gradlew :composeApp:assembleRelease -Pappversion=9.9.9`
Then: `cat composeApp/build/outputs/apk/release/output-metadata.json`
Expected: `"versionName": "9.9.9"` and `"versionCode": 90909`.

Then verify the local fallback:
Run: `./gradlew :composeApp:assembleRelease`
Then: `cat composeApp/build/outputs/apk/release/output-metadata.json`
Expected: `"versionName": "0.0.0-dev"` and `"versionCode": 1`.

- [ ] **Step 6: Commit**

```bash
git add composeApp/build.gradle.kts
git commit -m "build: derive app version from the appversion property"
```

---

### Task 2: Sign the Android release APK with the debug key

**Files:**
- Modify: `composeApp/build.gradle.kts` (add a `buildTypes` block inside `android { ... }`)

**Interfaces:**
- Consumes: nothing new (uses AGP's built-in `debug` signing config).
- Produces: `assembleRelease` now emits a signed, installable `composeApp-release.apk`.

- [ ] **Step 1: Observe the release APK is unsigned (red)**

Run: `./gradlew :composeApp:assembleRelease -Pappversion=9.9.9`
Then: `ls composeApp/build/outputs/apk/release/`
Expected (current, wrong): the file is named `composeApp-release-unsigned.apk` (the `-unsigned` suffix means it can't be installed).

- [ ] **Step 2: Add a `buildTypes` block to `android { ... }`**

Inside the `android { ... }` block (e.g. right after the `defaultConfig { ... }` block), add:

```kotlin
    buildTypes {
        getByName("release") {
            // TEMPORARY: sign the release build with the debug key so the APK is
            // installable via sideload from a GitHub Release. Replace this with a
            // real upload keystore backed by GitHub secrets before any Play upload.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
```

- [ ] **Step 3: Verify the release APK is now signed (green)**

Run: `./gradlew :composeApp:assembleRelease -Pappversion=9.9.9`
Then: `ls composeApp/build/outputs/apk/release/`
Expected: the file is named `composeApp-release.apk` (no `-unsigned` suffix).

Optional deeper check (if `apksigner` is on PATH via the Android SDK build-tools):
Run: `"$ANDROID_HOME"/build-tools/*/apksigner verify --print-certs composeApp/build/outputs/apk/release/composeApp-release.apk`
Expected: prints a certificate (the debug cert) and exits 0.

- [ ] **Step 4: Commit**

```bash
git add composeApp/build.gradle.kts
git commit -m "build: sign Android release APK with the debug key (temporary)"
```

---

### Task 3: Enable minified desktop release builds + add Linux target formats

**Files:**
- Create: `composeApp/proguard-rules.pro`
- Modify: `composeApp/build.gradle.kts` (`compose.desktop { application { ... } }`: add the ProGuard block, guard `outputBaseDir` to macOS, expand `targetFormats`)

**Interfaces:**
- Consumes: `appPackageVersion` and `isMacHost` (Task 1).
- Produces: the desktop `Release` packaging tasks (`runRelease`, `packageReleaseDmg`, `packageReleaseDistributionForCurrentOS`) now build a minified app with R8/ProGuard. `targetFormats` now includes `Deb` and `Rpm`. On non-macOS hosts, packaging output lands in the standard `composeApp/build/compose/binaries/` tree (needed by Task 5/6).

- [ ] **Step 1: Create the ProGuard keep rules**

Create `composeApp/proguard-rules.pro`:

```proguard
# Desktop entry point launched reflectively by the Compose/JVM launcher.
-keep class fr.dayview.app.MainKt { *; }

# JNA binds native code via reflection over these types.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }

# Our JNA Library interfaces (the Objective-C runtime bridges) are proxied by JNA.
-keep interface * extends com.sun.jna.Library { *; }

# Compose-generated resource accessors are reached by name.
-keep class fr.dayview.app.generated.resources.** { *; }

# Optional/desktop-only references that R8 can't see through.
-dontwarn org.jetbrains.**
-dontwarn com.sun.jna.**
```

- [ ] **Step 2: Enable ProGuard and guard `outputBaseDir` in `compose.desktop`**

In `composeApp/build.gradle.kts`, inside `compose.desktop { application { ... } }`, add the ProGuard block right after `mainClass = "fr.dayview.app.MainKt"`:

```kotlin
        buildTypes.release.proguard {
            isEnabled.set(true)
            obfuscate.set(false)
            configurationFiles.from(project.file("proguard-rules.pro"))
        }
```

Then wrap the existing `outputBaseDir.set(...)` call (inside `nativeDistributions { ... }`) in an `if (isMacHost)` guard. Replace:

```kotlin
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
            // This temp-dir redirect only exists to dodge a macOS
            // iCloud/FinderInfo codesign issue during jpackage. On Linux it
            // would push .deb/.rpm/app-image into $TMPDIR, so scope it to macOS.
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

In the same `nativeDistributions { ... }` block, replace:

```kotlin
            targetFormats(TargetFormat.Dmg)
```

with:

```kotlin
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Rpm)
```

- [ ] **Step 4: Verify the project still configures**

Run: `./gradlew :composeApp:tasks --group compose desktop -Pappversion=9.9.9`
Expected: configuration succeeds (no compile error), and the task list includes `packageReleaseDmg` and `packageReleaseDistributionForCurrentOS`.

- [ ] **Step 5: Minified launch smoke test (critical — macOS)**

Run: `./gradlew :composeApp:runRelease -Pappversion=9.9.9`
Expected: the DayView window opens and the menu-bar tray appears. Manually exercise: open/hide the window from the tray, and start a 25-minute Focus with an intention.
Watch the console for `ClassNotFoundException` / `NoSuchMethodError` / `MissingResourceException` — any of these means ProGuard stripped something. If so, add a matching `-keep` rule to `composeApp/proguard-rules.pro` and re-run until it launches and a Focus session runs cleanly. Quit via the tray's "Quitter DayView".

- [ ] **Step 6: Commit**

```bash
git add composeApp/build.gradle.kts composeApp/proguard-rules.pro
git commit -m "build: minify desktop release builds and add Linux deb/rpm formats"
```

---

### Task 4: Rewire the DMG customization to the release variant

**Files:**
- Modify: `composeApp/build.gradle.kts` (the `customizePackagedDmg` / `copyPackagedDmg` task definitions and the `tasks.matching { ... }` wiring at the bottom)

**Interfaces:**
- Consumes: `appPackageVersion` (Task 1); the minified release packaging from Task 3.
- Produces: `packageReleaseDmg` yields a customized, versioned DMG at `composeApp/build/compose/binaries/main-release/dmg/DayView-<version>.dmg`.

**Background:** the release packaging variant writes under `main-release/` (not `main/`), the customize task must key off `packageReleaseDmg` (not `packageDmg`), and the clean hook must also cover `createReleaseDistributable`. The DMG filename is currently the literal `DayView-1.0.0.dmg`.

- [ ] **Step 1: Point the customize task at the release DMG path**

In `composeApp/build.gradle.kts`, in the `customizePackagedDmg` task, replace:

```kotlin
    val packagedDmg = nativePackagingOutput.resolve("main/dmg/DayView-1.0.0.dmg")
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

(Leave the final `customizePackagedDmg.configure { finalizedBy(copyPackagedDmg) }` line unchanged.)

- [ ] **Step 4: Verify the customized release DMG is produced (macOS)**

Run: `./gradlew :composeApp:packageReleaseDmg -Pappversion=9.9.9`
Then: `ls composeApp/build/compose/binaries/main-release/dmg/`
Expected: `DayView-9.9.9.dmg` exists.
Optional: `hdiutil attach composeApp/build/compose/binaries/main-release/dmg/DayView-9.9.9.dmg` and confirm the volume shows the DayView app next to an `/Applications` shortcut, then `hdiutil detach` the mounted volume.

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
- Consumes: Compose's release app-image directory at `composeApp/build/compose/binaries/main-release/app/DayView` (produced by `packageReleaseDistributionForCurrentOS` / `createReleaseDistributable` on Linux), plus a PNG icon and a version.
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

- [ ] **Step 3: Verify the script is syntactically valid**

Run: `bash -n scripts/build_appimage.sh && test -x scripts/build_appimage.sh && echo OK`
Expected: prints `OK` (no syntax errors, file is executable). Full functional execution happens on Linux in the Task 6 CI dry run.

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
- Produces: a GitHub Release for each pushed `v*` tag, with the APK, DMG, DEB, RPM, and AppImage attached.

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
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
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
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
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
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
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
Expected: no errors. (If `actionlint` isn't installed, skip — the CI dry run in Step 4 is the real check.)

- [ ] **Step 3: Commit and push the workflow**

```bash
git add .github/workflows/release.yml
git commit -m "ci: add tag-triggered multiplatform release workflow"
git push -u origin claude/release-chain-multiplatform-43c6ae
```

- [ ] **Step 4: CI dry run with a throwaway tag**

The workflow only exists on the default branch once merged, but tag-triggered workflows run from the tagged commit's workflow file, so a test tag on this branch's HEAD works.

```bash
git tag v0.0.1-test
git push origin v0.0.1-test
gh run watch "$(gh run list --workflow=release.yml --limit 1 --json databaseId --jq '.[0].databaseId')"
```

Expected: all four jobs (android, linux, macos, release) succeed. Then confirm the Release assets:

```bash
gh release view v0.0.1-test --json assets --jq '.assets[].name'
```

Expected asset names (version `0.0.1-test`, package version `0.0.1`):
- `DayView-0.0.1-test.apk`
- `DayView-0.0.1.dmg`
- `DayView-0.0.1.x86_64.AppImage` (script uses the raw tag version → `0.0.1-test`; confirm exact name from the listing)
- a `.deb` (e.g. `dayview_0.0.1_amd64.deb`)
- a `.rpm` (e.g. `dayview-0.0.1-1.x86_64.rpm`)

> Note: the `.dmg`/`.deb`/`.rpm` carry the jpackage numeric version (`0.0.1`), while the `.apk` and `.AppImage` carry the raw tag string (`0.0.1-test`). This is expected given jpackage's numeric-only constraint.

- [ ] **Step 5: Clean up the test tag and release**

```bash
gh release delete v0.0.1-test --yes
git push origin --delete v0.0.1-test
git tag -d v0.0.1-test
```

- [ ] **Step 6: Manual Linux launch smoke test (not automatable from macOS)**

On a Linux machine (or VM), download the AppImage produced by the dry run (before deleting the release, or from a re-run), then:

```bash
chmod +x DayView-*.AppImage
./DayView-*.AppImage
```

Expected: the DayView window opens; macOS-only features (menu-bar status item, login launcher, focus-drift detection) are simply inactive; no crash from `Native.load`. If deleting the test release first, capture the AppImage locally beforehand.

---

## Self-Review

**1. Spec coverage:**
- GitHub Actions on tag → Task 6. ✓
- Four jobs (android/linux/macos + release publish) → Task 6. ✓
- GitHub Releases only, no secrets → Task 6 (`softprops/action-gh-release`, `permissions: contents: write`, no secrets referenced). ✓
- Version derived from tag; versionName/versionCode/packageVersion; dev fallback; numeric package version → Task 1. ✓
- DMG hardcoded-path fix + release variant path + clean hook for `createReleaseDistributable` → Task 4. ✓
- Minification (ProGuard enabled + keep rules) → Task 3. ✓
- `targetFormats` Dmg/Deb/Rpm; `outputBaseDir` macOS-only → Task 3. ✓
- Android release signed with debug config, not minified → Task 2. ✓
- Linux `.deb`/`.rpm` via Compose + `.AppImage` via `scripts/build_appimage.sh` + `rsvg-convert` icon → Tasks 5, 6. ✓
- Linux `Native.load` runtime risk → verified already guarded in the spec (no code task); covered by the Task 6 Step 6 smoke test. ✓
- Minified launch smoke test → Task 3 Step 5 (macOS) + Task 6 Step 6 (Linux). ✓

**2. Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to Task N" — all steps carry concrete code or commands. ✓

**3. Type consistency:** `appVersionName` / `appVersionCode` / `appPackageVersion` / `isMacHost` are defined once in Task 1 Step 2 and referenced consistently in Tasks 1–4. Task names `packageReleaseDmg`, `createReleaseDistributable`, `packageReleaseDistributionForCurrentOS`, `runRelease` used consistently. AppImage app-image path `main-release/app/DayView` matches `packageName = "DayView"`. ✓

**Known residual uncertainties (verified in the CI dry run, Task 6 Step 4):** exact jpackage `.deb`/`.rpm` filenames, and that `packageReleaseDistributionForCurrentOS` emits the app-image directory at the assumed path on the Linux runner. The `find`-based staging and the dry run are designed to surface these without guesswork.
