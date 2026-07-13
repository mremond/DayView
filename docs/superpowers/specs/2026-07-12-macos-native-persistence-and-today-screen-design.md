# macOS Native — Persistence + Today-Screen Parity (Path B, Phase 3)

## Context

Phases 1–2 (PR #45) built the native SwiftUI foundation: a `:core` KMP module, a
`DayViewKit` XCFramework, and a native app whose progress ring is driven by the real
`DayViewController` through a `StateFlow` → `DayViewSession` bridge emitting a primitives-only
`TodaySnapshot`. The native app is a proven vertical slice but not yet usable as a product:
preferences live only in the in-memory `DefaultDayPreferences` (every edit vanishes on
relaunch), and the window shows only the ring + a hardcoded-intention focus toggle.

Phase 3 makes the native window genuinely usable for the primary daily loop: **native
persistence** so edits survive relaunch, plus porting the two **self-contained** today-screen
sections — **goal** and **focus/pomodoro** — with native SwiftUI controls. Sections that need
platform data (net-time and focus-presence arcs) remain deferred to Phase 5.

## Goals

- Persist preferences natively on macOS, reusing the shared `DayPreferencesStore` encoding.
- Extend the Swift boundary (`TodaySnapshot` + `DayViewSession`) with goal and focus fields
  and actions, keeping it primitives-only.
- Port the goal and focus/pomodoro sections to native SwiftUI controls.
- Prove end to end: edit goal + focus in the native window, relaunch, values persist.

## Non-Goals (deferred)

- Detours (quick-capture dialog + list editor + suggestions).
- Net-time and focus-presence arcs (need EventKit / presence data — Phase 5).
- The focus-closure "how did it go" ritual (`closePomodoro`); `stopFocus` is enough here.
- Settings screen; menu-bar status item; mini window.

## Decisions (from brainstorming)

1. **Persistence:** move `DayPreferencesStore` into `:core` and add a native DataStore
   factory (option a) — reuse the shared encoding, not a per-platform fork.
2. **Scope:** persistence + focus/pomodoro + goal; detours deferred (option a).
3. **Native controls, not a Compose clone:** SwiftUI `TextField`/`DatePicker`/`Stepper`; the
   ring stays the centerpiece. Native feel over pixel-matching the Compose layout.
4. **Goal deadline flows as `Instant`/epoch-millis** through a new controller setter, not the
   Compose text-parse path.

## Architecture

### 1. Persistence — DataStore multiplatform in `:core`

`DayPreferencesStore` (the `DayPreferencesSnapshot` ↔ `Preferences` encoding) already lives in
`commonMain` and depends only on `androidx.datastore.preferences.core` (the **multiplatform**
artifact, 1.2.1, which has Kotlin/Native targets) plus kotlinx-coroutines.

- Add `datastore-preferences-core` to `:core` `commonMain`; move `DayPreferencesStore.kt` and
  `DayPreferencesStoreTest.kt` into `:core`. `:composeApp` (`DesktopPreferences`, Android)
  keeps using it from `:core` unchanged.
- Add a `:core/macosMain` factory:
  ```kotlin
  fun macosDayPreferences(): DayPreferences
  ```
  It creates a file-backed `DataStore<Preferences>` at
  `~/Library/Application Support/DayView/dayview.preferences_pb` via
  `PreferenceDataStoreFactory.createWithPath { path }` (okio path from
  `NSApplicationSupportDirectory`), wrapped in a `DayPreferences` implementation mirroring
  `DesktopPreferences`'s delegation to `DayPreferencesStore`.
- `DayViewNative.create()` uses `macosDayPreferences()` instead of `DefaultDayPreferences`.

**Invariant update:** `:core` may depend on multiplatform, Native-compatible androidx
libraries (datastore-preferences-core); the binding rule remains **no Compose and no
Android-only libraries — `:core` must compile for Kotlin/Native.**

### 2. Bridge + snapshot extensions

`TodaySnapshot` gains (still primitives-only):

- `goalTitle: String`
- `goalHasDeadline: Boolean`
- `goalDeadlineEpochMillis: Long` (0 when no deadline — seeds the SwiftUI `DatePicker`)
- `goalHoursRemaining: Long` (ceil of working time to the deadline; 0 when no deadline)
- `pomodoroMinutes: Long` (current focus-duration setting, for the `Stepper` display)

(`pomodoroStatus`, `pomodoroClock`, `focusIntention`, and the day-progress fields already
exist.)

`DayViewSession` gains thin pass-throughs:

- `setGoalTitle(title: String)`
- `setGoalDeadline(epochMillis: Long)` — 0 (or negative) clears the deadline
- `setFocusIntention(intention: String)`

(plus existing `startFocus`, `stopFocus`, `changePomodoroDuration`, `tick`).

`DayViewController` gains one Instant-based setter:

```kotlin
fun setGoalDeadlineInstant(deadline: Instant?)
```

It applies the same goal-start backfill logic as the existing `commitGoalDeadline` (when a
deadline is set and no start exists, or the existing start is at/after the new deadline, set
start = now), updates `goalDeadline`/`goalStart`/`goalStartText`, and persists — without going
through `goalDeadlineText`/`parseGoalDeadline`. The Compose UI keeps its text-based setters
untouched; this shares the backfill by extracting it into a private helper both call.

### 3. Native UI

`RingView` grows from a bare ring into a scrollable/stacked layout with three parts, all
reading `model.snapshot` reactively:

- **Ring** (centerpiece, unchanged).
- **Focus section:** an intention `TextField` bound to `model.setFocusIntention(...)` (on
  commit), a duration `Stepper` (5–180, step 5) calling `changePomodoroDuration(delta)`, and
  Start/Stop buttons. The status line shows `pomodoroStatus`/`pomodoroClock`.
- **Goal section:** a title `TextField` (→ `setGoalTitle`), a deadline `DatePicker` (→
  `setGoalDeadline(epochMillis)`), with a "clear" affordance, and a progress readout
  (`goalHoursRemaining` when `goalHasDeadline`).

`TodayModel` gains matching passthrough methods. Edits flow Swift → `DayViewSession` → controller
→ persisted snapshot → `StateFlow` → mapped `TodaySnapshot` → SwiftUI, the same reactive path
already proven.

## Data flow (edit + persist)

```
SwiftUI TextField/DatePicker/Stepper commit
  -> model.setGoalTitle / setGoalDeadline(millis) / setFocusIntention / changePomodoroDuration
  -> DayViewSession -> controller mutates state + persistState()
  -> macosDayPreferences() writes the snapshot to the Application Support DataStore file
  -> StateFlow emits -> toTodaySnapshot() -> @Published -> SwiftUI re-render
Relaunch: DayViewNative.create() -> macosDayPreferences() reads the file -> initial snapshot
```

## Testing / done criteria

- `DayPreferencesStore` tests pass from `:core` on JVM; add a native persistence round-trip
  test (write a non-default snapshot through the store, read it back through a fresh store
  instance, assert equality) runnable on the JVM target at minimum, plus `:core`
  `compileKotlinMacosArm64` proving the native factory compiles.
- New `:core` tests for `setGoalDeadlineInstant` (backfill parity with `commitGoalDeadline`)
  and the extended `toTodaySnapshot()` goal/focus fields.
- `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest` green
  (Android/desktop reuse the moved store).
- `:core:syncXCFramework` + `xcodebuild` succeed with the extended types.
- Native app: set a focus intention + duration and a goal title + deadline in the window;
  **quit and relaunch**; the values are still there and the ring/labels/goal readout reflect
  them.

## Risks surfaced by this phase

- **Native DataStore factory** (okio path in Application Support, 1.2.1 native artifacts
  resolving) — the primary new risk; retired first with the round-trip before UI work.
- **Moving `DayPreferencesStore` to `:core`** without breaking Android/desktop — guarded by
  the moved store test + the full gate.
- **`setGoalDeadlineInstant` backfill drift** from `commitGoalDeadline` — guarded by sharing a
  private helper and a parity test.

## Roadmap after this phase (context only)

Detours; native menu-bar status item + mini window; native platform integrations (EventKit
net-time, presence/focus-drift, login item, notifications, sound) lighting up the remaining
arcs; settings screen; packaging/signing/notarization + CI cutover from the JVM DMG to the
native `.app`; then the macOS Widget and finishing issue #18.
