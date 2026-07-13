# macOS Native Swift UI — Walking Skeleton (Path B)

## Context

DayView today is Kotlin Multiplatform / Compose Multiplatform. The macOS build runs on
the `jvm("desktop")` target using Compose Desktop (Skia/Skiko on the JVM) — the *same*
target that also produces the Linux `.deb`/`.rpm`/`.AppImage`. The Android build uses
Compose via `androidTarget()`.

We want macOS to become a **fully native SwiftUI app** for two reasons:

1. **Native feel** on macOS.
2. **Escape the Compose Desktop UI-bug class** — notably the known "main window does not
   recompose on interaction" issue on this stack.

The chosen strategy is **Path B**: keep the business logic in Kotlin, expose it to Swift
as a Kotlin/Native XCFramework, and write only the UI in SwiftUI. Linux stays on Compose
Desktop/JVM, so the shared Kotlin logic must compile **both** to JVM (Android + Linux) and
to a native XCFramework (macOS).

Because a native migration across a language boundary has many unknowns (KMP-Native build,
XCFramework packaging, Kotlin↔Swift interop ergonomics, native custom drawing), we start
with a **walking skeleton**: prove the whole pipeline end-to-end on the smallest real
slice, then port screen-by-screen in later specs.

### Relationship to issue #18

Issue #18 (AGP-9 migration) calls for restructuring `composeApp` into a standalone KMP
**library** subproject plus a separate Android **application** subproject, to remove the
`android.builtInKotlin=false` / `android.newDsl=false` bypass flags (which die in AGP 10).

The `:core` module introduced here is the **first, permanent increment** of that
restructure. It applies `com.android.library` (never `com.android.application`) for its
`androidTarget()` — exactly the supported KMP-library pattern #18 is steering toward — so
it is not throwaway and does not reintroduce #18's problem. This skeleton deliberately
does **not** complete #18: it does not split out the Android-application module and does
not move the Compose UI into a library module. Those remain separate #18 follow-ups.

## Goals

Prove, on a minimal vertical slice, that:

- Kotlin logic can be extracted into a Compose-free, multi-target `:core` module without
  breaking Android or Linux.
- `:core` assembles to a macOS XCFramework consumable from Xcode.
- A native SwiftUI app can call into that framework and render live, correct UI.
- The custom progress ring renders natively (no Compose recompose bug).

## Non-Goals (deferred to later specs)

Menu-bar status item, mini window, settings screen, focus/pomodoro, sound alerts,
EventKit calendar, presence tracking, login-at-launch, notifications, preference
persistence, app packaging / signing / notarization, CI integration, and the
`DayViewController` → `StateFlow` refactor. Completing issue #18 (Android-app split,
Compose UI into a library) is also out of scope.

## The slice

`DayProgress.kt` is already pure, multiplatform-safe Kotlin (`kotlinx.datetime` +
`kotlin.time`, zero Compose) and already computes both the ring geometry
(`currentMomentAngleDegrees`) and the remaining-time breakdown. `calculateDayProgress(now,
start, end)` is a pure function, so the skeleton drives it from a Swift-side timer and
needs **no** stateful controller refactor.

The SwiftUI window shows the live progress ring + centered remaining-time text for a
**hardcoded** working window (e.g. 09:00–18:00). No persistence, no preferences.

## Architecture

### 1. New Gradle module `:core`

A Compose-free KMP library with targets:

- `androidTarget()` — via `com.android.library` (namespace + `compileSdk` required)
- `jvm()` — for Linux desktop (and desktop tests)
- `macosArm64()` and `macosX64()` — for the XCFramework

Contents for the skeleton:

- `DayProgress.kt`, **moved** from `composeApp/src/commonMain` into `:core` commonMain.
  Its only external dependency is `kotlinx-datetime`.
- A new Swift-facing facade (see below).

`:composeApp` adds `implementation(project(":core"))`. Android and Linux therefore consume
the **same** `DayProgress` code — proving "shared", not "copied". Nothing else moves in
this skeleton.

### 2. Swift-friendly facade

`kotlin.time.Instant` / `Duration` and inline value classes do not bridge cleanly to
Objective-C/Swift. `:core` exposes a facade returning **primitives only**, so Swift never
touches a Kotlin value class:

```kotlin
object DayViewCore {
    fun dayProgress(
        nowEpochMillis: Long,
        startMinutes: Int,
        endMinutes: Int,
    ): DayProgressSnapshot
}

data class DayProgressSnapshot(
    val remainingSeconds: Long,
    val remainingRatio: Double,
    val momentAngleDegrees: Double,
    val isFinished: Boolean,
    val remainingHours: Long,
    val remainingMinutes: Long,
)
```

The facade converts the `Long` epoch-millis argument to `kotlin.time.Instant` internally,
calls the existing `calculateDayProgress`, and flattens the result. It is a **permanent**
architectural layer (the boundary all Swift screens will call through), not throwaway
skeleton code. A `commonTest` unit test pins the facade's flattening against
`calculateDayProgress` for a few representative inputs.

### 3. Build pipeline

Kotlin XCFramework DSL in `:core`:

```kotlin
kotlin {
    val xcf = XCFramework("DayViewCore")
    listOf(macosArm64(), macosX64()).forEach { target ->
        target.binaries.framework {
            baseName = "DayViewCore"
            xcf.add(this)
        }
    }
}
```

`./gradlew :core:assembleDayViewCoreXCFramework` produces
`core/build/XCFrameworks/release/DayViewCore.xcframework` (both arches).

### 4. Swift side

- A local Swift Package `macos/Packages/DayViewCore` declaring the framework as a
  `.binaryTarget` pointing at the Gradle-built `.xcframework`.
- An Xcode project `macos/DayView.xcodeproj` (SwiftUI app) depending on that package.
  Xcode project rather than a bare SPM executable, because upcoming phases need
  `Info.plist`, entitlements (EventKit), signing, and an `.app` bundle anyway.
- SwiftUI: a single `WindowGroup` containing a `Canvas` that reproduces the ring — a track
  circle plus a sweep arc starting at −90° spanning `(1 − remainingRatio) · 360°` — with
  centered remaining-time text. A 1 Hz `Timer` recomputes via `DayViewCore.dayProgress(…)`
  and redraws.

## Data flow

```
Swift Timer (1 Hz)
  -> now = Date().timeIntervalSince1970 * 1000  (epoch millis, Int64)
  -> DayViewCore.dayProgress(nowEpochMillis, startMinutes=540, endMinutes=1080)
  -> DayProgressSnapshot (primitives)
  -> SwiftUI state update -> Canvas redraw (ring arc + text)
```

Hardcoded `startMinutes = 540` (09:00), `endMinutes = 1080` (18:00) for the skeleton.

## Testing / done criteria

- `./gradlew :core:assembleDayViewCoreXCFramework` produces the XCFramework.
- `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest` stays
  green — Android and Linux are unaffected by moving `DayProgress` into `:core`.
- New `:core` `commonTest` facade test passes on JVM (and, where practical, native).
- Xcode builds a macOS `.app` that shows a live, ticking ring + remaining-time text with
  every value sourced from `:core`.

## Risks surfaced by this skeleton (the point of doing it first)

- **Interop ergonomics** — validated by the primitives-only facade; if even primitives
  bridge awkwardly, the facade is where we adapt.
- **XCFramework packaging & multi-arch** — validated by the assemble task + Xcode link.
- **Native ring rendering** — validated by the SwiftUI `Canvas` reproduction.
- **`:core` extraction not breaking JVM/Android** — validated by the existing test suite.

## Roadmap after the skeleton (context only — separate specs)

1. `DayViewController` → `StateFlow`; port the full today screen (goal, pomodoro controls).
2. Native menu-bar status item + mini window (native `NSPanel`).
3. Settings screen.
4. Native platform integrations (EventKit, login item, focus drift, notifications, sound)
   replacing the current helper-binary shelling.
5. Packaging + signing + notarization; CI cutover of the macOS release from the JVM DMG to
   the native `.app`.
6. Complete issue #18 (Android-application split, Compose UI into a library module).
