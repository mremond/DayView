# Calendar TCC responsible-process stability — design (follow-up)

## Why this follow-up exists

The first design (`2026-07-13-macos-calendar-access-stability-design.md`) signed the
EventKit *helper* so its code identity is stable across rebuilds. Verification (Task 3)
showed the calendar permission **still re-prompts**. Debugging found the reason.

## Root cause (evidence-backed)

macOS TCC keys a calendar grant to the **responsible process**, not to whichever binary
calls EventKit. The helper is a child process spawned via `ProcessBuilder`
(`CalendarSource.desktop.kt`); unless it disclaims responsibility, TCC walks up the
process tree and attributes the request to the ancestor that "owns" it.

Evidence:

- The macOS Allow dialog names **"DayView"** (the app bundle), not "DayView Calendar
  Helper" — so TCC attributes to the bundle, confirmed from the installed app.
- `codesign -dv /Applications/DayView.app` shows `flags=0x2(adhoc)`,
  `TeamIdentifier=not set`. The bundle is **ad-hoc signed**, so its code identity changes
  on every repackage → TCC forgets the grant → re-prompt.
- The existing `macOS { }` comment in `composeApp/build.gradle.kts` already notes "macOS
  attributes the calendar request to this app bundle" — the attribution model was known;
  a stable signature for the bundle was the missing piece.

Signing the helper was **necessary but not sufficient**: it stabilised the *requesting*
binary, but TCC keys on the *responsible* one.

Two launch contexts have two responsible processes:

- **Packaged `/Applications/DayView.app`** → responsible = the app bundle (`fr.dayview.app`).
- **`./gradlew :composeApp:run`** → responsible = whatever launched the JVM (terminal app
  or `java`). **Pending confirmation** from the dev-mode Allow dialog name before Fix B is
  finalised.

## Fix A — sign the app bundle with a stable Developer ID identity (packaged app)

Replace the ad-hoc bundle signature with the developer's Developer ID Application identity,
so the responsible process's Designated Requirement is stable across repackages and TCC
keeps the grant.

Developer ID signing enables **hardened runtime**, which blocks a JVM app from loading its
native libraries (JNA's `jnidispatch`, Compose/Skia) and from JIT unless entitled. So Fix A
is "sign **and** ship the right entitlements", not a lone flag.

### Signing

Use Compose Desktop's macOS signing DSL, identity from `local.properties`
(`dayview.macos.signingIdentity`, the same key Fix from the first design already reads):

```kotlin
macOS {
    // ...
    signing {
        sign.set(macosSigningIdentity != null)
        macosSigningIdentity?.let { identity.set(it) }
    }
    entitlementsFile.set(rootProject.layout.projectDirectory.file("composeApp/entitlements.plist"))
    runtimeEntitlementsFile.set(rootProject.layout.projectDirectory.file("composeApp/runtime-entitlements.plist"))
}
```

- Identity absent (CI / Linux / contributors) → `sign.set(false)` → ad-hoc as today. No
  build failure anywhere.
- The bundled EventKit helper lives inside a jar as resource bytes and is already signed at
  build time (first design's Task 2); the outer bundle signature seals it by hash and does
  not re-sign or conflict with it.

### Entitlements

Explicit files (not relying on Compose defaults, which vary by version). Both the app and
the bundled JRE need the JVM hardened-runtime set:

`composeApp/entitlements.plist` and `composeApp/runtime-entitlements.plist`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>com.apple.security.cs.allow-jit</key><true/>
    <key>com.apple.security.cs.allow-unsigned-executable-memory</key><true/>
    <key>com.apple.security.cs.disable-library-validation</key><true/>
    <key>com.apple.security.cs.allow-dyld-environment-variables</key><true/>
</dict>
</plist>
```

Rationale: `disable-library-validation` lets JNA load its extracted native stub;
`allow-jit` / `allow-unsigned-executable-memory` cover the JVM; `allow-dyld-environment-variables`
covers jpackage's launcher env. The app is **not** sandboxed, so no
`com.apple.security.personal-information.calendars` entitlement is required — the Info.plist
usage description already present is what drives the prompt. `get-task-allow` is deliberately
omitted (it would break a future notarization).

### Not in scope for A

Notarization. TCC persistence on the developer's own machine needs only a stable Developer
ID signature; notarization is a Gatekeeper concern for distribution to *other* machines and
is deferred.

## Fix B — disclaim responsibility for the helper (dev `gradlew run`), CONDITIONAL

Intent: spawn the helper with `POSIX_SPAWN_SETDISCLAIM` (via `responsibility_spawnattrs_setdisclaim`)
so the helper becomes its **own** responsible process, keyed to its stable Developer ID
identity — fixing the dev loop and hardening the packaged app uniformly. JNA is already a
desktop dependency, so the private libSystem call is reachable without new dependencies.

**Gate:** finalise B only after reading the dev-mode Allow dialog name:

- Dialog names the **terminal app** (stable identity) → the dev grant may already persist;
  B may be unnecessary or only prevents polluting the terminal's grant. Re-evaluate scope.
- Dialog names something **unstable** → B as above is the fix.

The `posix_spawn` rework (manual stdin/stdout pipe wiring to preserve the line-based
protocol) is non-trivial and will not be written until the gate evidence is in.

## Verification

- `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest` green.
- Fix A acceptance (developer machine, decisive):
  1. `./gradlew installMac` with `dayview.macos.signingIdentity` set.
  2. Confirm the app **launches** (hardened runtime + entitlements correct — a wrong
     entitlement set manifests as an immediate crash on launch).
  3. `codesign -dv --verbose=4 /Applications/DayView.app` → `Authority=Developer ID
     Application: ProcessOne`, `flags=0x10000(runtime)`, `TeamIdentifier=8L55BDM864`.
  4. Grant calendar access once; confirm busy time renders.
  5. Repackage + reinstall; relaunch; confirm calendar reads **without** a new prompt.
- Fix B acceptance: same grant-once → rebuild → relaunch loop under `./gradlew :composeApp:run`.

## Out of scope

- Notarization and Gatekeeper handling for distribution.
- Android calendar permissions.
