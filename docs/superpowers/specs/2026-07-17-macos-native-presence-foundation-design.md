# macOS Native — Presence foundation + engaged display (Path B, Phase 10a)

## Context

The native macOS app (Path B; phases 1–9b merged) tracks a Focus session's duration but not
*where* the user actually was during it. On the Compose/JVM app, while a Focus is active the
app samples the frontmost application once a second, classifies it against a user-chosen set of
"on-goal" apps, and accumulates **engaged** time — drawn as bright mint arcs on the ring with a
"Focus H h MM" total, and feeding the clean-session ledger via `sessionOffGoal`. The user
configures the on-goal apps on a settings screen (a running-apps picker).

Almost all the logic is already in `:core` commonMain (`PresenceAccumulator`,
`SessionCleanlinessTracker`, `classifyFrontmost`/`OnGoalState`, `AppRef`, `deriveEngagedIntervals`,
the `focusArcsState`/`focusSessionBandsState`/`focusedToday` projections, and the
`setOnGoalApps`/`setFocusPresenceIntervals`/`setFocusSessionIntervals`/`setSessionOffGoal`
setters). What is missing natively: a frontmost-app source, the per-tick loop that drives the
accumulators (currently inline in the JVM `Main.kt`), the engaged arcs on the dial, the on-goal
settings screen, and the wiring of `sessionOffGoal`.

Phase 10 is split (approved): **10a (this spec)** is the presence foundation, the engaged
display, and the on-goal settings — the visible, self-contained half. **10b (later)** adds the
attention layer: drift nudges, the notification, the dock badge/bounce, and the resume ritual.

## Goals

- A native frontmost-app + running-apps provider (`NSWorkspace`), injected into the session.
- A tested `:core` presence coordinator that drives the accumulators per tick (extracting the
  JVM `Main.kt` presence block), plus relocating the pure drift/resume detectors into `:core`.
- Engaged/deep-focus arcs on the dial + the "Focus H h MM" total.
- An "On-goal apps" settings section (running-apps picker).
- `sessionOffGoal` flowing into the clean-session ledger (closing the Phase 5a gap).

## Non-Goals (deferred)

- **10b:** drift nudges (the 4-switch / 2-min-off-goal rules), the `UNUserNotificationCenter`
  notification, the dock badge, the dock bounce, and the resume ritual (found-active-on-relaunch
  → window to front). The detectors are *relocated* in 10a but not *wired*.
- The focus-session detail hover popup (a separate checklist item; the `FocusSessionBand` carries
  its `record`, but 10a draws the band without hover).
- Engaged arcs in the mini window (the JVM mini has none — main-window-only, matching busy/detour).
- Any behavior change to the presence algorithms — the existing `:core` accumulators/classifier
  are used as-is.

## Decisions (from brainstorming)

1. **10a foundation+engaged / 10b attention** split.
2. **`CalendarSource`-pattern architecture** (7a precedent): a native provider is injected into
   the session; the per-tick loop logic lives in `:core` (testable), Swift only reads
   `NSWorkspace`.
3. **Consolidate the presence logic into `:core`** — the coordinator *and* the pure
   drift/resume detectors move to `:core` in 10a, so 10b is purely native providers + wiring.

## Architecture

### `:core` — the frontmost provider, the coordinator, and the detector migration

**`FrontmostAppProvider` interface** (commonMain, mirrors `CalendarSource`):

```kotlin
interface FrontmostAppProvider {
    fun frontmostBundleId(): String?   // the frontmost app's bundle id, or null
    fun runningApps(): List<AppRef>    // regular running apps, for the on-goal picker
}
object NoopFrontmostAppProvider : FrontmostAppProvider { /* null / emptyList */ }
```

**`PresenceCoordinator`** (commonMain) — extracts the JVM `Main.kt` per-tick presence block
into a testable unit. It owns the strict on-goal `PresenceAccumulator` (bridge 60s) and the
looser session accumulator (ON_GOAL+NEUTRAL, bridge 120s, minInterval 60s, interruptionGap
15s) and a `SessionCleanlinessTracker`, matching the JVM parameters exactly. Per tick:

```kotlin
class PresenceCoordinator {
    data class Result(
        val presenceIntervals: List<FocusPresenceInterval>,
        val sessionIntervals: List<FocusPresenceInterval>,
        val sessionOffGoal: Duration,
    )
    fun observe(
        now: Instant,
        isFocusActive: Boolean,
        frontmostBundleId: String?,
        onGoalBundleIds: Set<String>,
        pomodoroEnd: Instant?,
        dayKey: Long,
    ): Result
}
```

Semantics copied from `Main.kt`: classify via `classifyFrontmost(frontmostBundleId,
onGoalBundleIds)`; while active, feed both accumulators and the cleanliness tracker; when a
session ends, close the runs. Never throws.

**Detector migration:** move `FocusDriftDetector` and `FocusResumeDetector` (pure Kotlin, no
JVM/JNA in their logic) from `shared/src/desktopMain/.../FocusDriftDetector.kt` into `:core`
commonMain (same package `fr.dayview.app`, so the JVM `Main.kt` keeps resolving them with no
import change). `MacFrontmostApplicationProvider` (JNA) stays in `desktopMain`. Their tests move
from `desktopTest` to `:core` (`jvmTest` if they use JVM-only test APIs, else `commonTest`).
10a does not wire the detectors — this is a preparatory relocation so 10b is native-only. (This
is the project's first `:shared`→`:core` logic migration — the pattern the sync phase will
reuse.)

### Native provider + session wiring

`NSWorkspaceFrontmostProvider` in `macosMain`:
- `frontmostBundleId()` → `NSWorkspace.sharedWorkspace.frontmostApplication?.bundleIdentifier`.
- `runningApps()` → `NSWorkspace.sharedWorkspace.runningApplications`, filtered to
  `activationPolicy == .regular`, mapped to `AppRef(bundleIdentifier, localizedName)`.

**No TCC prompt** — the frontmost app's bundle id and the running-app list are public API, not
screen recording. (Same as the JVM provider.)

`DayViewNative.create()` injects it; `DayViewSession` gains a `frontmostAppProvider` constructor
param (default `Noop`) and a `PresenceCoordinator`. On each `tick()`, when a focus is active, the
session reads the frontmost bundle id, runs the coordinator with the controller's current
`onGoalApps`/`pomodoroEnd`, and pushes the result via `setFocusPresenceIntervals`/
`setFocusSessionIntervals`/`setSessionOffGoal`. Idle ticks do nothing.

### Snapshot + engaged arcs

`TodaySnapshot` gains:

```kotlin
val focusArcs: List<FocusArcSnapshot>,          // bright on-goal stretches
val focusSessionBands: List<FocusArcSnapshot>,  // faint band across each session window
val focusTotalLabel: String,                    // "Focus H h MM" from focusedToday, else ""
```

with `data class FocusArcSnapshot(val startAngleDegrees: Double, val sweepDegrees: Double)` (no
color — the mint accent is fixed; no hover in 10a, so the band's `record` is not carried).
Mapped from `focusArcsState`/`focusSessionBandsState`/`focusedToday`.

`DayRingCanvas` draws them **on the main ring lane** (not a separate concentric lane, unlike
busy/detour), the deferred Phase 8 layers, in JVM order and style: first the session bands —
`palette.mint` @ 0.18, stroke `lineWidth × 0.5`, round caps — then the on-goal arcs —
`palette.mint` @ 0.55, stroke `lineWidth × 0.5` — drawn after the sweep and before the busy
lane so the bright arcs read on top of the track. `focusTotalLabel` joins the dial's interior
lines (muted). Main window only; `MiniView` passes nothing. No hover.

### On-goal apps settings

A new "On-goal apps" section in `SettingsView`: a `TodayModel.runningApps()` list (from the
provider, `selectableApps` drops DayView), each row a toggle whose on-state means the app is in
`onGoalApps`; toggling calls `setOnGoalApps(nextSet)`. The currently-selected set shows as on.
Bridge: `setOnGoalApps(bundleIds:names:)` (or an add/remove pair) delegating to
`controller.setOnGoalApps(Set<AppRef>)`; `runningApps()` surfaces the provider list to Swift.

### `sessionOffGoal` → ledger

The coordinator returns `sessionOffGoal`; the session pushes it via `setSessionOffGoal`, so
`closeFocus`'s `closePomodoro` evaluates real off-goal time in the clean-session ledger — the
documented Phase 5a limitation is closed.

## Data flow

```
DayViewSession.tick() [focus active]
  -> provider.frontmostBundleId()
  -> PresenceCoordinator.observe(now, active, bundleId, onGoalApps, pomodoroEnd, dayKey)
  -> controller.setFocusPresenceIntervals / setFocusSessionIntervals / setSessionOffGoal
  -> stateFlow emits -> snapshot.focusArcs / focusSessionBands / focusTotalLabel
  -> DayRingCanvas main-lane arcs + interior "Focus H h MM"
Settings on-goal toggle -> setOnGoalApps -> classification uses the new set next tick
closeFocus -> closePomodoro reads sessionOffGoal for the clean-session ledger
```

## Testing / done criteria

- **`:core:jvmTest`:**
  - `PresenceCoordinator` with a fake provider: staying in an on-goal app grows the engaged
    intervals; an off-goal app accrues `sessionOffGoal`; no focus → empty result. (The
    accumulator internals are already tested; this pins the coordinator's wiring/params.)
  - Snapshot mapping: with presence intervals, `focusArcs`/`focusSessionBands` carry the
    expected angles and `focusTotalLabel` matches `^Focus \d+ h \d{2}$|^Focus \d+ min$`
    (or the exact `formatDurationHm`-derived string); empty when no presence.
  - The migrated `FocusDriftDetectorTest`/`FocusResumeDetectorTest` pass unchanged in their new
    `:core` home; the JVM build still compiles (Main.kt resolves the relocated detectors).
- **Native:** `./gradlew :core:runMacNative` → `** BUILD SUCCEEDED **`. Manual smoke test:
  add an on-goal app in Settings; start a Focus and stay in that app → bright mint arcs grow on
  the dial and "Focus H h MM" appears; switch to a non-on-goal app → the arcs stop growing;
  close the focus → the clean-session outcome reflects the off-goal time.

## Risks

- **`:shared`→`:core` detector migration breaking the JVM build** — mitigated by keeping the
  same package (`fr.dayview.app`) so `Main.kt` needs no import change, and by the full gate
  (`:shared:desktopTest` etc.) which exercises the JVM path. Split `FocusDriftDetector.kt`
  carefully: the two detectors move, `MacFrontmostApplicationProvider` stays.
- **Frontmost sampling cost** — one `NSWorkspace` read per second on the main thread while a
  focus is active; negligible, and only during a session. Idle ticks skip it.
- **`runningApps()` freshness** — the picker reads the list when Settings opens; apps launched
  afterward won't appear until reopened. Acceptable (the JVM refreshes on open too); a manual
  refresh is out of scope.
- **Coordinator parameter drift** — the two accumulators' bridge/minInterval/interruptionGap
  values must match `Main.kt` exactly, or engaged time diverges from the JVM. Copied verbatim
  and pinned by the coordinator test.
- **Native presence persistence (10a limitation)** — engaged presence intervals are IN-MEMORY
  ONLY on native: `focusPresenceIntervals` has no key in `:core`'s `DayPreferencesStore`, so
  the mint arcs and the Focus total reset on every relaunch, not just mid-session. Separately,
  `focusSessionIntervals` IS persisted by the store, but `DayViewNative.create()` doesn't seed
  it back as `initialFocusSessionIntervals`, so `PresenceCoordinator.restore()` currently always
  seeds empty on native — effectively inert. Impact is bounded in 10a: the arcs are cosmetic and
  rebuild during a session, and the clean-session ledger is unaffected (`closePomodoro` reads the
  live in-memory `sessionOffGoal`). A native presence-persistence path is a tracked follow-up.

## Roadmap after this phase (context only)

10b: wire the (now `:core`) drift + resume detectors on the same frontmost feed; the
`UNUserNotificationCenter` nudge (needs notification permission), the dock badge, the dock
bounce, and the resume ritual. Then the focus-session detail hover popup, must-dos, sounds,
history, day-over, sync, i18n, and the packaging/CI cutover.
