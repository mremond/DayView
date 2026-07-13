# macOS Native — Reactive State Foundation (Path B, Phase 2)

## Context

The Path-B walking skeleton (spec `2026-07-12-macos-native-swift-walking-skeleton-design.md`,
merged via PR #45) proved the KMP→Swift pipeline: a `:core` module, a `DayViewKit`
XCFramework, and a native SwiftUI app drawing the day-progress ring from a **pure function**
(`DayViewCore.dayProgress`) on a Swift-side timer, for a hardcoded 09:00–18:00 window.

Phase 2 replaces that hardcoded, stateless slice with the app's **real reactive state**. It
moves the Compose-free domain layer and `DayViewController` into `:core`, converts the
controller from Compose's `mutableStateOf` to a `StateFlow`, and bridges that flow to SwiftUI
through a small hand-written observer. This retires the one genuinely new risk in the native
migration — **observing a Kotlin `StateFlow` from SwiftUI** — on a minimal slice before any
feature-complete screen work.

## Goals

- Move the pure-domain layer + `DayViewController` into `:core`, compiling for Kotlin/Native.
- Replace `mutableStateOf` with a `StateFlow`, keeping Android + Linux (Compose) green.
- Expose a hand-written, dependency-free observation bridge emitting a flattened
  `TodaySnapshot`, plus a small `DayViewActions` facade.
- Prove the reactive round-trip natively: the ring is driven by the real controller, and a
  live action (start/stop focus) updates the SwiftUI UI through Kotlin and back.

## Non-Goals (deferred to later specs)

- Native persistence — the native app uses the existing in-memory `DefaultDayPreferences`.
- All other today-screen features: goal-progress bar UI, net-time/calendar arcs, focus arcs,
  detours, full pomodoro controls, settings, menu bar, mini window.
- Adopting a Flow-bridging plugin (SKIE / KMP-NativeCoroutines) — explicitly rejected in
  favour of the hand-written wrapper while the flow surface is tiny.

## Decisions (from brainstorming)

1. **Scope:** reactive-state foundation only (not persistence, not the full screen).
2. **Bridge:** hand-written, dependency-free wrapper — no Gradle compiler plugin.
3. **Swift boundary:** a flattened `TodaySnapshot` + `DayViewActions` facade; the rich
   `DayViewUiState` stays internal to `:core`.
4. **Persistence:** reuse the existing in-memory `DefaultDayPreferences`; no new storage.

## Architecture

### 1. Domain + controller move into `:core`

The controller currently imports only `androidx.compose.runtime.{getValue, mutableStateOf,
setValue}` — no `@Composable`. Removing `mutableStateOf` removes its last Compose dependency,
letting it compile for `macosArm64`/`macosX64`.

**Move into `:core/commonMain`** (all verified Compose-free; the only `androidx` mentions in
these files are comments):

- `Pomodoro.kt`, `GlobalGoal.kt`, `PresenceAccumulator.kt`, `SoundAlerts.kt`,
  `OnGoalApps.kt`, `OnGoalState.kt`, `Detours.kt`, `ThemeMode.kt`
- `CalendarNetTime.kt` — the pure net-time types (`CalendarInfo`, `BusyInterval`) and
  functions (`busyArcs`, `calculateNetTime`, `dayWindow`, focus/detour arc helpers)
- `DayPreferences.kt` — `DayPreferences` interface, `DayPreferencesSnapshot`,
  `DefaultDayPreferences`
- `DayViewController.kt` — `DayViewController`, `DayViewUiState`, `DayViewDestination`,
  `SettingsCategory`, and the private snapshot/coercion helpers
- The corresponding tests: `DayViewControllerTest`, `PomodoroTest`, `GlobalGoalTest`,
  `DetoursTest`, `FocusArcsTest`, `PresenceAccumulatorTest`, `SoundAlertsTest`,
  `ThemeModeTest`, `CalendarNetTimeTest`, `OnGoalAppsTest`, `OnGoalClassificationTest`,
  and the `InMemoryDayPreferences` helper.

**Stays in `:composeApp`:**

- `GoalStrings.kt` — contains `@Composable` string helpers (UI); the controller does not use
  it.
- `DayPreferencesStore.kt` — Android DataStore persistence.
- `CalendarSource` (expect/actual) — the platform calendar reader. The controller never reads
  the calendar; it only *receives* results via `updateNetTimeData(...)`. Only the pure data
  types/functions from `CalendarNetTime.kt` move; the reader stays platform-side.

If any moved file turns out to reference a symbol that must stay behind (e.g. a type still
defined next to `CalendarSource`), that symbol moves with it or is relocated so `:core`
remains Compose/androidx-free. The invariant: **`:core` has no Compose or androidx
dependency.**

### 2. Controller: `mutableStateOf` → `StateFlow`

```kotlin
internal class DayViewController(
    private val preferences: DayPreferences,
    private val scope: CoroutineScope,
    initialSnapshot: DayPreferencesSnapshot,
    initialNow: Instant = Clock.System.now(),
) {
    private val _stateFlow = MutableStateFlow(initialSnapshot.toUiState(initialNow))
    val stateFlow: StateFlow<DayViewUiState> = _stateFlow.asStateFlow()

    // Internal reads/writes replace `state` with `_stateFlow.value`.
}
```

Every `state = state.copy(...)` becomes `_stateFlow.update { it.copy(...) }`; every read of
`state` becomes `_stateFlow.value`. Behaviour (self-write echo suppression via
`selfWritesInFlight`, init backfill, all actions) is otherwise unchanged.

**Compose consumers adapt:** wherever `composeApp` reads `controller.state`, it switches to
`controller.stateFlow.collectAsState().value` (or `collectAsStateWithLifecycle`). This is a
small, mechanical edit to the existing Android/Linux UI, covered by the existing
`desktopTest`/Android UI tests.

### 3. Swift-facing snapshot + actions

`TodaySnapshot` (primitives + simple value structs), mapped from `DayViewUiState`:

```kotlin
data class TodaySnapshot(
    // day progress (as in DayProgressSnapshot)
    val remainingSeconds: Long,
    val remainingRatio: Double,
    val momentAngleDegrees: Double,
    val isFinished: Boolean,
    val remainingHours: Long,
    val remainingMinutes: Long,
    // focus / pomodoro
    val pomodoroStatus: String, // "IDLE" | "ACTIVE" | "BREAK"
    val pomodoroClock: String,  // formatted mm:ss / break text, "" when idle
    val focusIntention: String,
    // headline text
    val dayStatus: String,      // remaining-time headline or "day over"
    val goalStatus: String,     // goal line, "" when no goal
)
```

Mapping lives in `:core` as `fun DayViewUiState.toTodaySnapshot(): TodaySnapshot`, unit-tested.

Observation bridge (hand-written, in `:core`):

```kotlin
class DayViewObserver(private val controller: DayViewController) {
    fun subscribe(onEach: (TodaySnapshot) -> Unit): Cancellable
}

interface Cancellable { fun cancel() }
```

`subscribe` launches a collector on an internal `CoroutineScope` (main dispatcher), maps each
`DayViewUiState` to `TodaySnapshot`, and invokes `onEach`. `Cancellable.cancel()` cancels the
scope. The bridge owns threading explicitly so later phases understand the model before adding
more flows.

`DayViewActions` facade (thin pass-throughs): `tick()`, `startFocus(intention: String)`,
`stopFocus()`, `changePomodoroDuration(deltaMinutes: Int)`.

A single native entry point assembles these with the in-memory preferences, e.g.
`DayViewNative.create(): DayViewSession` exposing `observer`, `actions`, and lifecycle
teardown, so Swift constructs the whole graph in one call.

### 4. Native SwiftUI wiring

```swift
final class TodayModel: ObservableObject {
    @Published var snapshot: TodaySnapshot
    private let session = DayViewNative.shared.create()
    private var cancellable: Cancellable?
    private var timer: Timer?
    // init: snapshot = initial; cancellable = session.observer.subscribe { self.snapshot = $0 }
    //       timer -> session.actions.tick() every 1s
}
```

`RingView` reads `model.snapshot` (replacing its direct `DayViewCore.dayProgress` call).
Two buttons — "Start focus" / "Stop focus" — call `session.actions.startFocus("…")` /
`stopFocus()`, and the ring/labels update through the observed flow.

## Data flow

```
Swift Timer (1 Hz) -> actions.tick()
  -> controller._stateFlow.update { now = … }
  -> StateFlow emits DayViewUiState
  -> DayViewObserver maps -> TodaySnapshot (main dispatcher)
  -> onEach closure -> @Published snapshot -> SwiftUI re-render

Swift button -> actions.startFocus("…") -> controller state change -> same path
```

## Testing / done criteria

- Moved domain tests run green from `:core` on JVM (`./gradlew :core:jvmTest`), including
  `DayViewControllerTest` against the `StateFlow` controller.
- New `:core` test asserts `DayViewUiState.toTodaySnapshot()` maps day-progress, pomodoro, and
  headline fields correctly.
- `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest` green — the
  Compose UI compiles and tests pass against `stateFlow.collectAsState()`.
- `:core:assembleDayViewKitXCFramework` + `swift build` succeed with the new types.
- Native app: the ring is driven by the controller (not a hardcoded window); pressing
  "Start focus" flips `pomodoroStatus` to `ACTIVE` and updates the focus label live, then
  "Stop focus" returns it to `IDLE` — proving the reactive round-trip end to end.

## Risks surfaced by this phase

- **StateFlow → Swift observation & threading** — the core new risk; retired by the
  hand-written bridge + the live start/stop-focus round-trip.
- **De-Compose-ing the controller without behaviour drift** — guarded by the moved
  `DayViewControllerTest` and the unchanged action logic.
- **Domain move entanglement (expect/actual, DataStore)** — bounded by the "`:core` stays
  Compose/androidx-free" invariant and the explicit stays-behind list.

## Roadmap after this phase (context only)

Native persistence; then the today-screen features (goal bar, net-time/calendar arcs, focus
arcs, detours, full pomodoro controls); then menu bar + mini window; settings; native
platform integrations; packaging + CI cutover; and completing issue #18.
