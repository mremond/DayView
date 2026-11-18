# Native macOS packaging — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A local script that builds the native SwiftUI app in Release, signs it with the user's Developer ID, notarizes and staples it, and produces a DMG. It must be usable today for personal use, and become the official macOS release artifact after the history work.

**Architecture:**
- **Project settings:** `macos/project.yml` gains a Release signing setup: hardened runtime, an entitlements file with the calendar entitlement, a secure timestamp, and the marketing version from the build.
- **Script:** `scripts/package-native-macos.sh` builds the XCFramework, generates the project, archives or builds in Release with the identity from `local.properties`, verifies the signature, builds and signs a DMG, notarizes it with a `notarytool` keychain profile, staples it, and assesses it with `spctl`.
- **Unchanged:** CI is left as is. The switch of the official release is a later step.

**Tech Stack:** Bash, XcodeGen, xcodebuild, codesign, hdiutil, xcrun notarytool/stapler, spctl.

**Decided by the user (2026-11-18):** signing and notarization run locally (no secrets in GitHub). The native app replaces the Compose DMG in releases only after the history work.

## Global Constraints

- **Release identity:** bundle ID `fr.dayview.app`, app name `DayView`. The Debug configuration keeps `fr.dayview.app.debug` and `CODE_SIGNING_ALLOWED: NO` for development builds, unchanged.
- **Signing identity:** read from `local.properties` key `dayview.macos.signingIdentity` (the same key the Compose packaging uses), overridable by env `DAYVIEW_SIGNING_IDENTITY`. It must exist in `security find-identity -v -p codesigning`, otherwise the script fails with a clear message.
- **Notary profile:**
  - It comes from `local.properties` key `dayview.macos.notaryProfile`, overridable by env `DAYVIEW_NOTARY_PROFILE`, with default `dayview-notary`.
  - If the profile is missing, the script fails with the exact one-time command to run: `xcrun notarytool store-credentials dayview-notary --apple-id <id> --team-id <team> --password <app-specific password>`.
  - `--skip-notarize` builds and signs without notarizing.
- **Hardened runtime and entitlements:**
  - Hardened runtime is on.
  - The entitlements file `macos/DayView/DayView.entitlements` contains `com.apple.security.personal-information.calendars = true`.
  - The app is **not** sandboxed (no `com.apple.security.app-sandbox`), matching the Compose app.
  - The signature uses `--timestamp`.
  - The embedded `DayViewKit` framework is signed by the same identity.
- **Version:** the script's first argument is a numeric version such as `1.4.0` (`MARKETING_VERSION`); `CURRENT_PROJECT_VERSION` is a numeric build from `git rev-list --count HEAD`.
- **Output:** `dist/DayView-<version>.dmg` (directory `dist/`, git-ignored). The DMG contains `DayView.app` and an `/Applications` symlink. The DMG itself is signed, notarized and stapled unless `--skip-notarize` is given.
- **Verification inside the script:**
  - `codesign --verify --deep --strict --verbose=2` on the app;
  - `codesign -d --entitlements - ` shows the calendar entitlement;
  - `spctl --assess --type execute` on the app (only after notarization);
  - `spctl --assess --type open --context context:primary-signature` on the DMG (after stapling).
- The script is `set -euo pipefail`, prints each phase, never prints secrets, and never touches `~/.dayview`, the user's installed apps or `/Applications`.
- Docs: `.claude/CLAUDE.md` and `README.md` gain a short "Native macOS app" packaging section: prerequisites, the one-time notarytool command, and how to run the script.
- Commits in English, no AI references, no reference to `docs/superpowers/`.

## Review Focus

1. **Missing signing identity or notary profile.** The script fails early, before building, with an actionable message.
2. **A machine with several Developer ID identities.** The script uses exactly the configured one, never "first found".
3. **Kotlin framework not built, or stale.** The script rebuilds it (`./gradlew :core:syncXCFramework`) every time.
4. **Notarization rejected.** The script prints the notarytool log for the submission and exits non-zero, without stapling.
5. **Re-running with the same version.** It overwrites `dist/DayView-<version>.dmg` cleanly; a leftover mounted volume or temp dir from a failed run doesn't break the next one.

---

### Task 1: Release signing settings and entitlements

**Files:** `macos/project.yml` (modify), `macos/DayView/DayView.entitlements` (new), `.gitignore` (add `dist/` if missing).

- [ ] **Step 1:** Add the entitlements file. In `project.yml`, the Release config gets:
  - `CODE_SIGN_ENTITLEMENTS: DayView/DayView.entitlements`
  - `ENABLE_HARDENED_RUNTIME: YES`
  - `CODE_SIGNING_ALLOWED: YES`
  - `CODE_SIGN_STYLE: Manual`
  - `OTHER_CODE_SIGN_FLAGS: --timestamp`

  The identity is passed at build time by the script, not stored in the file. Debug stays `CODE_SIGNING_ALLOWED: NO`.
- [ ] **Step 2:** Verify that Debug still builds:
  `cd macos && xcodegen generate && xcodebuild -project DayView.xcodeproj -scheme DayView -configuration Debug -destination 'platform=macOS' build CODE_SIGNING_ALLOWED=NO -quiet`.
- [ ] **Step 3:** Verify that a Release build signs locally, without notarization:
  `xcodebuild -project DayView.xcodeproj -scheme DayView -configuration Release -destination 'platform=macOS' build CODE_SIGN_IDENTITY="<identity from local.properties>" -quiet`, then `codesign --verify --deep --strict` and an entitlements dump.
  If codesign blocks on a keychain access prompt, stop and report BLOCKED (the user must allow it); do not try to bypass keychain security.
- [ ] **Step 4: Commit** `git commit -m "Sign native Release builds with the hardened runtime and calendar entitlement"`

### Task 2: Packaging script and docs

**Files:** `scripts/package-native-macos.sh` (new, executable), `.claude/CLAUDE.md`, `README.md` (modify).

- [ ] **Step 1:** Write the script to the Global Constraints, with these phases:
  1. preflight (identity, notary profile unless `--skip-notarize`, xcodegen, xcodebuild);
  2. `./gradlew :core:syncXCFramework`;
  3. `xcodegen generate`;
  4. a Release build into `build/native-release` (derived data `-derivedDataPath`), with version settings and identity;
  5. verify the app signature and entitlements;
  6. stage it into a temp dir with an `Applications` symlink;
  7. `hdiutil create -format UDZO`;
  8. `codesign` the DMG with `--timestamp`;
  9. `notarytool submit --keychain-profile … --wait`, printing `notarytool log <id>` on a non-Accepted status and exiting 1;
  10. `stapler staple`;
  11. `spctl` assessments;
  12. print the output path.

  Clean up the temp dir on exit (`trap`).
- [ ] **Step 2:** Run `scripts/package-native-macos.sh 1.0.0-local --skip-notarize`, or a numeric version per the constraints, e.g. `0.9.0`. Expected: a signed `dist/DayView-0.9.0.dmg`, `codesign --verify` passes, and the entitlements include calendars. Do not notarize; that needs the user's credentials. Mounting the DMG read-only to check its contents is allowed, but unmount it.
- [ ] **Step 3:** Add the docs sections.
- [ ] **Step 4: Commit** `git commit -m "Add a local script to sign, notarize and package the native macOS app"`
