# Multiplatform release chain (macOS, Linux, Android)

Date: 2026-07-11
Status: Approved design

## Goal

Wire up automated release builds that fire when a version tag is pushed and
produce installable artifacts for macOS, Linux, and Android, published to a
single GitHub Release for the tag. This is the first release chain for the
project; there is currently no CI and no git tags.

## Context

- Kotlin Multiplatform / Compose Multiplatform app (`DayView`). Targets
  `androidTarget()` and `jvm("desktop")`.
- Compose `1.11.1`, Kotlin `2.3.20`, AGP `8.12.3`. JDK 17 baseline.
- GitHub remote: `git@github.com:mremond/DayView.git`. No `.github/workflows`.
- Desktop packaging currently emits only macOS `.dmg`
  (`targetFormats(TargetFormat.Dmg)`), with macOS-only native pieces: a Swift
  `MacFocusStatusHelper` compiled via `xcrun`, JNA `Native.load("objc")`, and a
  menu-bar tray. These are already guarded by `onlyIf { os == Mac }` (Gradle
  tasks) and `System.getProperty("os.name").startsWith("Mac")` (runtime code),
  so a Linux build compiles and launches with those features inactive.
- Android currently builds only a debug APK; there is no release signing config.
- Hardcoded version strings today: Android `versionCode = 1`,
  `versionName = "1.0"`; Compose `packageVersion = "1.0.0"`; and the
  `customizePackagedDmg` / `copyPackagedDmg` Gradle tasks reference the literal
  path `main/dmg/DayView-1.0.0.dmg`.

## Decisions

Locked during brainstorming:

- **Mechanism:** GitHub Actions triggered on tag push.
- **Publish target:** GitHub Releases only. No store upload, no CI-only
  artifacts.
- **Signing:** Skipped for this first chain. Android release APK is signed with
  the existing **debug** signing config so it stays sideload-installable; macOS
  `.dmg` and Linux packages are unsigned.
- **Linux formats:** AppImage + `.deb` + `.rpm`.
- **Android artifact:** APK (not AAB).
- **Version wiring:** fixed in `build.gradle.kts` (approved), so the tag is the
  single source of truth for all three platforms.

## Architecture

Single workflow `.github/workflows/release.yml`, trigger `on: push: tags: ['v*']`.
Four jobs:

```
tag v1.2.0 pushed
  ├─ job: android   (ubuntu-latest)  → DayView-1.2.0.apk
  ├─ job: linux     (ubuntu-latest)  → DayView-1.2.0.AppImage, .deb, .rpm
  ├─ job: macos     (macos-latest)   → DayView-1.2.0.dmg
  └─ job: release   (needs: [android, linux, macos])
                                      → GitHub Release for the tag, all artifacts attached
```

- Each build job uploads its output via `actions/upload-artifact`.
- The `release` job downloads all artifacts and publishes the Release with
  `softprops/action-gh-release` (creates the Release for the tag, attaches
  files). No secrets required.
- Common setup per build job: `actions/checkout`, `actions/setup-java`
  (Temurin 17), `gradle/actions/setup-gradle` for caching. The Android job also
  runs `android-actions/setup-android`.

### Version derivation

The workflow strips the leading `v` from the tag ref (`v1.2.0` → `1.2.0`) and
passes it to every Gradle invocation as `-Pappversion=1.2.0`.

`build.gradle.kts` reads the `appversion` project property with a dev-default
fallback (e.g. `"0.0.0-dev"`) for local builds where the property is absent:

- Android `versionName` = the version string.
- Android `versionCode` = derived monotonic integer from the semver:
  `major*10000 + minor*100 + patch` (e.g. `1.2.0` → `10200`). Non-numeric
  pre-release suffixes are stripped before parsing; the dev fallback yields a
  small constant so local builds don't fail.
- Compose `packageVersion` = the version string.

### Gradle changes

1. Read `appversion` once and expose the version string + derived
   `versionCode`.
2. Apply the version to Android `defaultConfig` and Compose
   `nativeDistributions.packageVersion`.
3. **Fix the hardcoded DMG path**: `customizePackagedDmg` and `copyPackagedDmg`
   must reference the configured `packageVersion` instead of the literal
   `DayView-1.0.0.dmg`, so they keep working as the version changes.
4. Expand desktop `targetFormats` to
   `TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage`.
   Compose only builds formats valid for the current OS, so macOS runners still
   produce just the `.dmg` and Linux runners produce the three Linux formats.
5. Point the Android `release` build type at the existing **debug** signing
   config, with a comment marking it temporary until a real upload keystore is
   added. This keeps `assembleRelease` output installable via sideload.

### Per-platform build commands

- **Android** (`ubuntu-latest`): `./gradlew :composeApp:assembleRelease
  -Pappversion=$VERSION`. Rename the output to `DayView-<version>.apk`.
- **Linux** (`ubuntu-latest`): `apt-get install` `rpm` and `fakeroot` (jpackage
  needs them for `.rpm`), then `./gradlew
  :composeApp:packageDistributionForCurrentOS -Pappversion=$VERSION`.
  Swift-helper and DMG tasks are skipped by their existing `onlyIf { Mac }`.
- **macOS** (`macos-latest`): existing `./gradlew :composeApp:packageDmg
  -Pappversion=$VERSION` flow (the `customizePackagedDmg`/`copyPackagedDmg`
  finalizers stay wired to `packageDmg`). `xcrun`/Swift helper work on the
  runner.

Both desktop jobs use the non-`Release` packaging tasks (no R8/minification),
matching the current README workflow.

## Runtime risk (Linux)

During an active Focus session on Linux, `Main.kt` calls
`frontmostApplicationProvider.bundleIdentifier()`. Verify that path is guarded
like its siblings; if not, add a one-line `isMacOS` guard so the Linux app can't
trip `Native.load("objc")`. Small and contained.

## Testing / verification

- **Local (this Mac):** confirm the Gradle changes still produce a working
  `.dmg` and a release APK when a version is passed via `-Pappversion`, e.g.
  `./gradlew :composeApp:packageDmg :composeApp:assembleRelease
  -Pappversion=9.9.9` — check the artifact filenames carry the version and the
  DMG customize/copy tasks still succeed.
- **CI dry run:** push a throwaway tag (e.g. `v0.0.1-test`) once the workflow
  lands and confirm all three artifacts attach to the resulting Release. Delete
  the test tag/Release afterward.
- **Linux launch** cannot be fully exercised from the Mac; flag it as a manual
  smoke test (download the AppImage on a Linux box and confirm the window opens
  with Mac-only features inactive).

## Scope boundaries (YAGNI)

Explicitly out of scope, left as clean follow-up seams:

- Google Play / any store upload; Android AAB.
- macOS notarization and Developer-ID signing.
- Real Android upload keystore (swap the debug signing config for secrets).
- Windows target.
- Changelog / release-notes generation.
