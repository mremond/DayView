# macOS calendar access stability — design

## Problem

On macOS the calendar (TCC) grant is tied to a **binary code identity**, derived from
the code signature's Designated Requirement (DR) — not to "the app named DayView". Two
things reset that identity on every dev iteration, so macOS re-prompts for calendar
access after each rebuild:

1. **The EventKit helper is never signed with a stable identity.** It is compiled with
   `swiftc` and left unsigned (`compileMacEventKitHelper` in
   `composeApp/build.gradle.kts`). On Apple Silicon the linker applies an **ad-hoc**
   signature whose `cdhash` is content-derived, so it changes on every recompile. New
   cdhash → new program → new TCC prompt. An ad-hoc signature can never be stabilised.

2. **The helper is extracted to a random temp path on every launch.**
   `Files.createTempFile("dayview-eventkit-", "")` in `CalendarSource.desktop.kt`
   produces a fresh path each run. For unsigned/ad-hoc code TCC also keys on the path, so
   even without a rebuild each launch looks new.

## Goal

Grant calendar access once and keep it across rebuilds and relaunches during local
development, without weakening the build for machines that lack a signing certificate
(CI, Linux, other contributors).

## Approach

Give the helper a **stable code identity** (a real Developer ID certificate the developer
already has) and extract it from a **stable path**. For validly Developer-ID-signed code
TCC records the grant against the DR, independent of cdhash and path — so the grant
survives rebuilds.

### 1. Sign the EventKit helper with a configurable identity

After `compileMacEventKitHelper`, code-sign the helper when a signing identity is
configured:

```
codesign --force --options runtime \
         --identifier fr.dayview.app.eventkit-helper \
         --sign "<identity>" \
         <macos-eventkit-helper>
```

- The fixed `--identifier fr.dayview.app.eventkit-helper` (matching the embedded
  `Info.plist` `CFBundleIdentifier`) keeps the DR identical across rebuilds.
- The signature is embedded in the Mach-O, so it survives the runtime copy to the
  extraction path and does not disturb the `__info_plist` section that carries the usage
  description.
- `--options runtime` (hardened runtime) is harmless locally and keeps the door open for
  notarising the distributed app later.

**Identity is read from `local.properties`**, key `dayview.macos.signingIdentity`, e.g.:

```
dayview.macos.signingIdentity=Developer ID Application: ProcessOne (8L55BDM864)
```

`local.properties` is already per-machine and gitignored (it also carries the Android SDK
path). The build must **not** hard-code the identity.

**Fallback:** when the key is absent, or the host is not macOS, the signing step is
skipped and the current unsigned behaviour is preserved. No build fails on CI, Linux, or
a contributor's machine. Skipping is announced with a short Gradle `log`/`println` so the
absence of signing is visible rather than silent.

### 2. Stable extraction path

Replace the random `createTempFile` extraction with a **fixed, content-addressed** path so
the same helper binary always lands at the same location and is reused between runs.

- Directory: `~/Library/Application Support/DayView/helpers/` (created on demand).
- Filename: `eventkit-helper-<hash>` where `<hash>` is a short hex prefix of the SHA-256 of
  the bundled resource bytes. A new build changes the bytes → new filename; an unchanged
  build reuses the existing file (skip the copy when it already exists with the right
  size/permissions).
- Executable permission (owner r/w/x) is set as today.

Because the same binary maps to the same path, TCC never sees a "new" location, and the
disk is not littered with per-run temp files.

### 3. Shared extraction helper (avoid duplication)

`MacFocusStatusItem.kt` extracts its own bundled helper with the same
`createTempFile` + POSIX-permission pattern. Introduce one small internal function, e.g.
`extractBundledExecutable(resourceName: String): java.nio.file.Path`, in a shared desktop
file, and route **both** the EventKit source and the focus-status item through it. This
removes the duplicated extraction logic (per the repo code-style guide) and gives both
helpers the stable-path behaviour. Only the EventKit helper is code-signed; the focus
helper needs no TCC grant, so signing stays specific to the EventKit compile task.

## Components touched

- `composeApp/build.gradle.kts` — read `dayview.macos.signingIdentity` from
  `local.properties`; add a `codesign` step wired after `compileMacEventKitHelper` and
  before `desktopProcessResources`/`desktopTestProcessResources`; `onlyIf` macOS host and
  identity present.
- `composeApp/src/desktopMain/kotlin/fr/dayview/app/` — new shared
  `extractBundledExecutable(...)`; `CalendarSource.desktop.kt` and `MacFocusStatusItem.kt`
  call it instead of `createTempFile`.
- `.claude/CLAUDE.md` — one line documenting the `local.properties` key and that calendar
  permission on dev builds depends on it.

## Error handling / edge cases

- **No identity configured** → skip signing, log once, behave as today.
- **`codesign` fails** (bad identity, revoked cert) → the Gradle step fails loudly with
  codesign's message; the developer fixes `local.properties`. We do not silently fall back
  from a *configured-but-broken* identity, to avoid masking a misconfiguration.
- **Extraction dir not writable** → surface the IO error as today (the helper simply
  cannot start; calendar features degrade to the no-op path).
- **Cache purge / manual delete of the extracted file** → re-extracted identically on next
  run; since it is Developer-ID-signed the DR is unchanged, so TCC still recognises it.

## Testing / verification

- `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest` green.
- Signing/extraction is macOS- and Keychain-dependent, so it is not unit-tested. The
  decisive check is manual TCC behaviour, performed on the dev machine:
  1. Build and run; grant calendar access once.
  2. `./gradlew :composeApp:run` again after a clean rebuild.
  3. Confirm the app reads the calendar **without** a new permission prompt.
- Only after observing step 3 is the feature considered done — TCC is finicky and the fix
  is not claimed working on the strength of the code alone.

## Out of scope

- Signing/notarising the packaged `DayView.app` for end-user distribution (separate
  concern; end users grant once at install anyway).
- Any change to Android calendar permissions.
