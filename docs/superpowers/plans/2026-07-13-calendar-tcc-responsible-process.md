# Calendar TCC responsible-process stability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the macOS calendar grant persist across rebuilds by stabilising the *responsible process* TCC keys on — starting with Fix A: sign the packaged app bundle with a stable Developer ID identity plus the hardened-runtime entitlements a JVM app needs.

**Architecture:** macOS attributes the calendar request to the app bundle (the responsible process), which is currently ad-hoc signed and therefore unstable across repackages. Fix A signs the bundle with the developer's Developer ID identity (read from `local.properties`) via Compose Desktop's macOS signing DSL, and ships explicit app + runtime entitlements so the signed, hardened-runtime JVM app still loads JNA/Skia and launches. When no identity is configured, signing is disabled and the ad-hoc behaviour is unchanged.

**Tech Stack:** Compose Multiplatform Desktop 1.11.1 (jpackage), Gradle Kotlin DSL, macOS `codesign` / hardened runtime.

## Global Constraints

- Signing identity comes only from `local.properties` key `dayview.macos.signingIdentity`; never hard-coded. Absent identity or non-macOS host → signing disabled, ad-hoc behaviour preserved, no build failure on CI/Linux/contributors.
- ktlint enforced; explicit imports (no wildcards). Full gate green, no stderr: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`.
- Commit messages describe the change only — no Claude/Anthropic/AI reference, no test-plan/verification section.
- The `macosSigningIdentity` val already exists near the top of `composeApp/build.gradle.kts` (added by the earlier signing work); reuse it, do not redefine it.
- App is not sandboxed: do NOT add `com.apple.security.app-sandbox` or `personal-information.*` entitlements. Do NOT add `get-task-allow` (would break future notarization).

---

### Task A1: Developer ID sign the app bundle with hardened-runtime entitlements

**Files:**
- Create: `composeApp/entitlements.plist`
- Create: `composeApp/runtime-entitlements.plist`
- Modify: `composeApp/build.gradle.kts` (add `signing { }`, `entitlementsFile`, `runtimeEntitlementsFile` inside the existing `macOS { }` block, ~lines 177-192)

**Interfaces:**
- Consumes: the `macosSigningIdentity: String?` val already defined in `composeApp/build.gradle.kts`.
- Produces: a `createDistributable`/`packageDmg` output `DayView.app` that is Developer-ID signed with hardened runtime when an identity is configured on macOS; ad-hoc otherwise.

- [ ] **Step 1: Create the app entitlements file**

Create `composeApp/entitlements.plist`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>com.apple.security.cs.allow-jit</key>
    <true/>
    <key>com.apple.security.cs.allow-unsigned-executable-memory</key>
    <true/>
    <key>com.apple.security.cs.disable-library-validation</key>
    <true/>
    <key>com.apple.security.cs.allow-dyld-environment-variables</key>
    <true/>
</dict>
</plist>
```

- [ ] **Step 2: Create the runtime entitlements file**

Create `composeApp/runtime-entitlements.plist` with identical content (the bundled JRE needs the same hardened-runtime allowances):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>com.apple.security.cs.allow-jit</key>
    <true/>
    <key>com.apple.security.cs.allow-unsigned-executable-memory</key>
    <true/>
    <key>com.apple.security.cs.disable-library-validation</key>
    <true/>
    <key>com.apple.security.cs.allow-dyld-environment-variables</key>
    <true/>
</dict>
</plist>
```

- [ ] **Step 3: Wire signing + entitlements into the macOS block**

In `composeApp/build.gradle.kts`, inside the existing `macOS { }` block (which currently sets `bundleID`, `iconFile`, `infoPlist { }`), add the signing configuration. Place it after the `infoPlist { }` block, before the closing brace of `macOS {`:

```kotlin
                // Developer ID signing keeps the app bundle's code identity stable across
                // rebuilds, so macOS (which attributes the calendar request to this bundle)
                // keeps the permission granted. Identity comes from local.properties; when
                // unset the bundle is ad-hoc signed and the permission resets on each build.
                signing {
                    sign.set(macosSigningIdentity != null)
                    macosSigningIdentity?.let { identity.set(it) }
                }
                // Developer ID signing turns on the hardened runtime, which otherwise blocks
                // the JVM from JIT and from loading JNA/Skia native libraries. These
                // entitlements restore what a JVM app needs. App is not sandboxed.
                entitlementsFile.set(
                    rootProject.layout.projectDirectory.file("composeApp/entitlements.plist"),
                )
                runtimeEntitlementsFile.set(
                    rootProject.layout.projectDirectory.file("composeApp/runtime-entitlements.plist"),
                )
```

- [ ] **Step 4: Build the signed app bundle**

Run: `./gradlew :composeApp:createDistributable`
Expected: BUILD SUCCESSFUL. The app is written under the redirected mac output base
(`$TMPDIR/dayview-compose-package`). Locate it:

Run: `find "$(getconf DARWIN_USER_TEMP_DIR 2>/dev/null || echo "$TMPDIR")"dayview-compose-package -name 'DayView.app' -maxdepth 4 2>/dev/null; find /var/folders /tmp -name 'DayView.app' -path '*dayview-compose-package*' 2>/dev/null | head -1`
(If the redirect path is hard to resolve, instead read it from the build: the base dir is `System.getProperty("java.io.tmpdir")/dayview-compose-package`, subpath `main/app/DayView.app`.)

- [ ] **Step 5: Verify the signature and hardened runtime**

With `APP` set to the located `DayView.app`:

Run: `codesign -dv --verbose=4 "$APP" 2>&1 | grep -E "Authority|TeamIdentifier|flags|Identifier"`
Expected: `Identifier=fr.dayview.app`, `Authority=Developer ID Application: ProcessOne (8L55BDM864)`, `TeamIdentifier=8L55BDM864`, and `flags=0x10000(runtime)` (hardened runtime on).

Run: `codesign --verify --strict --deep --verbose=2 "$APP" 2>&1 | tail -3`
Expected: `valid on disk` and `satisfies its Designated Requirement`, exit 0.

- [ ] **Step 6: Smoke-test that the signed, hardened app launches**

A wrong entitlement set manifests as an immediate crash. Launch the executable headless-ish and confirm it stays alive ~4 seconds, then kill it:

Run:
```bash
"$APP/Contents/MacOS/DayView" >/tmp/dayview-launch.log 2>&1 &
PID=$!
sleep 4
if kill -0 "$PID" 2>/dev/null; then echo "LAUNCH OK"; kill "$PID"; else echo "CRASHED"; tail -20 /tmp/dayview-launch.log; fi
```
Expected: `LAUNCH OK`. If `CRASHED`, read the log and `/tmp/dayview-launch.log` for a hardened-runtime/library-validation error and report BLOCKED with that output — do not guess at entitlements.

- [ ] **Step 7: Run the full gate**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, no stderr.

- [ ] **Step 8: Commit**

```bash
git add composeApp/entitlements.plist composeApp/runtime-entitlements.plist composeApp/build.gradle.kts
git commit -m "Sign the macOS app bundle with a stable Developer ID identity"
```

**Note (manual acceptance, owner: developer, not this task):** install the signed build
(`./gradlew installMac`), grant calendar access once, repackage + reinstall, relaunch, and
confirm the calendar reads without a new prompt. This is the decisive check for Fix A and
is done by the developer.
