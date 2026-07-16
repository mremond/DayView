# macOS Native — EventKit source + net-time readout (Path B, Phase 7a)

## Context

The native SwiftUI macOS app (Path B; phases 1–6 merged) has no calendar integration. On
the Compose/JVM app, **net time** subtracts calendar-busy periods from the remaining day:
a `CalendarSource` reads busy events (on macOS via a JNA-loaded Swift dylib,
`scripts/MacEventKitBridge.swift`), `App.kt` probes it every minute and injects the result
through `DayViewController.updateNetTimeData(hasPermission, busyIntervals,
availableCalendars, readError)`, and the controller computes `netTime` and the busy arcs
itself. All the calculation logic (`CalendarNetTime.kt`, clip-merge-fold, focus-block
exclusion) is already in `:core` commonMain.

Phase 7 brings net time to the native app in two increments. **7a (this spec):** the data
path end-to-end — a Kotlin/Native EventKit source, session polling, the numeric net-time
readout under the countdown, and the settings controls. **7b (later):** busy arcs on the
ring + hover details.

## Goals

- `EventKitCalendarSource` in `:core` macosMain implementing the existing `CalendarSource`
  interface via Kotlin/Native's `platform.EventKit` — no JNA, no Swift reader.
- The session polls the source (JVM cadence: once a minute) and injects via the existing
  `updateNetTimeData` — with the probe logic extracted to a shared, testable helper.
- Snapshot + bridge surface for: the net-time label, permission/error state, the calendar
  checklist, and the enable/include/request actions.
- Settings gains a "Net time" section; both windows gain the `Net X h MM` secondary line.

## Non-Goals (deferred)

- **7b:** busy arcs on the ring, hover event details.
- Upcoming-days availability on the day-over screen (`updateUpcomingData` — untouched).
- Calendar change-push observation (`CalendarSource.observeChanges` — the default no-op
  stands, as on JVM desktop; the minute cadence is the refresh).
- JVM adoption of the shared probe helper (`App.kt` keeps its inline probe for now).
- Packaged-app signing/entitlements (recorded below as a packaging-phase requirement).
- i18n (native copy stays hardcoded English).

## Decisions (from brainstorming)

1. **Kotlin/Native EventKit source** — implements `CalendarSource` in `:core` macosMain;
   Swift stays pure UI; no raw event data crosses the FFI.
2. **7a/7b split** — data + readout + settings first; arcs are pure rendering on data 7a
   already delivers.

## Architecture

### `EventKitCalendarSource` (`core/src/macosMain`)

Implements `CalendarSource` against `platform.EventKit`:

- **One `EKEventStore` for the process lifetime.** The primed-store lesson (JVM bug, fixed
  2026-07-16): a fresh store returns empty calendars/events even when authorized. Access is
  requested on this instance; every read reuses it.
- `isSupported()` → `true`. `hasPermission()` → `EKEventStore.authorizationStatusForEntityType
  (EKEntityTypeEvent) == EKAuthorizationStatusFullAccess`.
- `requestPermission()` → `requestFullAccessToEventsWithCompletion` (macOS 14+ API; our
  floor is 15). The completion hops to the main queue and invokes an injectable
  `onPermissionChange` callback so the session can refresh immediately after a grant.
- `availableCalendars()` → `calendarsForEntityType(event)` mapped to
  `CalendarInfo(id = calendarIdentifier, displayName = title)`.
- `busyIntervals(windowStart, windowEnd, includedCalendarIds)` → a
  `predicateForEventsWithStartDate/endDate` query over the included calendars (empty set =
  all), mapped to `BusyInterval(start, end, titles = listOf(event.title), calendarId)`,
  filtering **exactly** as `scripts/MacEventKitBridge.swift` does (the JVM contract — each
  platform reader filters):
  - skip `isAllDay` events;
  - skip events whose `availability != EKEventAvailabilityBusy`;
  - skip events the current user declined or marked tentative (participant status);
  - **travel-time deduction** (added to the JVM bridge 2026-07-16): read the event's travel
    time via the private KVC key `"travelTime"` guarded by `respondsToSelector` (a future OS
    removing the accessor degrades to "no travel"), reject non-finite values, clamp to
    `0..3h`; extend the busy interval **upstream** (`busyStart = startDate − travel`); widen
    the fetch window by the 3 h clamp past `windowEnd` so an event starting after the window
    whose travel reaches into it is still seen, and drop events whose extended start is
    still `>= windowEnd`.
- **Threading:** all store access happens on the main thread (the session's dispatcher);
  reads are same-process predicate queries. No background queue in 7a.

### Shared probe helper (`core/src/commonMain`)

`App.kt`'s inline probe becomes a reusable, fake-testable function:

```kotlin
data class NetTimeProbeResult(
    val permission: Boolean,
    val busy: List<BusyInterval> = emptyList(),
    val calendars: List<CalendarInfo> = emptyList(),
    val readError: Boolean = false,
)

fun probeNetTime(
    source: CalendarSource,
    enabled: Boolean,
    includedCalendarIds: Set<String>,
    windowStart: Instant,
    windowEnd: Instant,
): NetTimeProbeResult
```

Semantics copied from `App.kt`: disabled → `permission = false`, nothing read; enabled
without permission → `permission = false`; enabled with permission → read busy intervals
(a thrown read surfaces as `readError = true` with an empty list — "the read failed" is
distinct from "no events") and the calendar list (a failed calendar read degrades to an
empty list). Never throws.

### Session wiring (`DayViewSession` / `DayViewNative`)

- `DayViewSession` gains a constructor parameter
  `calendarSource: CalendarSource = NoopCalendarSource` (existing tests stand unchanged).
- `DayViewNative.create()` constructs `EventKitCalendarSource` and passes it in.
- **Refresh triggers:** once at session creation; every 60th `tick()` (the JVM's 1/min
  cadence); immediately after `setNetTimeEnabled`/`setCalendarIncluded`; and on the
  source's `onPermissionChange` (post-grant). Each refresh runs `probeNetTime` with the
  controller's current state (enabled, included ids, today's `dayWindow`) and calls
  `controller.updateNetTimeData(...)`.

### Bridge surface

`TodaySnapshot` gains (existing conventions):

```kotlin
val netTimeEnabled: Boolean,
val calendarPermission: Boolean,   // netCalendarPermission
val calendarReadError: Boolean,    // netCalendarError
val netTimeLabel: String,          // "Net " + formatDurationHm(netTime.netRemaining) — e.g.
                                   // "Net 4 h 12" or "Net 45 min" (the JVM net_remaining
                                   // format) — when enabled && netTime != null, else ""
val calendars: List<CalendarChoice>,
```

with a new primitives-only nested type:

```kotlin
data class CalendarChoice(
    val id: String,
    val displayName: String,
    val included: Boolean, // EFFECTIVE inclusion: id in the set, or the set is empty (= all)
)
```

`calendars` mirrors the raw `state.availableCalendars` — the same list the JVM settings
checklist binds (`DayViewSettingsScreen.kt:162`), NOT the day-gated `availableCalendarsToday`
(which is empty on days without busy events and would hide the checklist).
`formatDurationHm` already lives in `:core` (`CalendarNetTime.kt`).

`DayViewSession` actions (each triggers an immediate refresh):

```kotlin
fun setNetTimeEnabled(enabled: Boolean)          // copies netTimeSettings with the flag
fun setCalendarIncluded(id: String, included: Boolean)
    // nextIncludedCalendars(allIds, current, id, included) -> setNetTimeSettings
fun requestCalendarAccess()                      // source.requestPermission()
```

`TodayModel` mirrors the three actions one line each.

### Settings — "Net time" section (`SettingsView`)

Appended after the Display section, mirroring the JVM copy (hardcoded English):

- Header "Net time" with the description "Subtracts busy slots from your calendar and greys
  them out on the circle." (grey-out arrives in 7b; the copy stays the product wording).
- `Toggle("Net time calculation")` bound to `netTimeEnabled` / `setNetTimeEnabled` (the
  Phase 6 no-local-state binding pattern).
- When enabled and `!calendarPermission`: a "Grant calendar access" button →
  `requestCalendarAccess()`, with a caption explaining macOS will prompt.
- When `calendarReadError`: a warning line ("Calendar could not be read.").
- When enabled and `calendarPermission`: one `Toggle` per `CalendarChoice`
  (`displayName`, bound to `included` / `setCalendarIncluded(id:included:)`).
- Footnote: "Only events marked busy (excluding all-day events) are subtracted."

### Ring readout

`RingView` and `MiniView` render `netTimeLabel` as a muted secondary line under the
countdown when non-empty — the exact `secondsLabel` mechanism and placement (net line
below the seconds line when both show). The headline `dayStatus` is untouched: net time is
secondary information, as on the JVM.

## Packaging / plumbing facts (recorded deliberately)

- `macos/project.yml` gains
  `INFOPLIST_KEY_NSCalendarsFullAccessUsageDescription` (e.g. "DayView subtracts the time
  of your busy calendar events from the remaining day."). Without the usage string the
  macOS 14+ access request fails outright.
- The dev build (`:core:runMacNative`) is unsigned with no hardened runtime, so the TCC
  prompt works as-is, under the distinct `fr.dayview.app.debug` identity (its grant is
  separate from the shipping app's).
- **Future packaging phase:** the Developer-ID/hardened-runtime build will need the
  `com.apple.security.personal-information.calendars` entitlement — the same root cause the
  JVM app hit. Recorded here; not part of 7a.

## Data flow

```
DayViewSession (creation / 60th tick / settings change / permission grant)
  -> probeNetTime(EventKitCalendarSource, enabled, includedIds, dayWindow(now))
  -> controller.updateNetTimeData(permission, busy, calendars, readError)
  -> stateFlow emits -> snapshot: netTimeLabel / calendarPermission / calendars ...
  -> SettingsView (toggle, grant button, checklist), RingView/MiniView (net line)
Settings actions -> session setters -> controller.setNetTimeSettings -> refresh
"Grant calendar access" -> source.requestPermission -> macOS prompt -> onPermissionChange
  -> refresh -> snapshot flips calendarPermission, calendars fill in
```

## Testing / done criteria

- **`probeNetTime` unit tests** (`:core` commonTest, runs under `:core:jvmTest`, fake
  `CalendarSource`): disabled → no permission, source not asked; enabled without
  permission → `permission = false`; happy path → intervals + calendars through; a source
  that throws on `busyIntervals` → `readError = true`, empty busy, calendars still read.
- **Session tests** (`DayViewSessionTest`, fake source injected): creation triggers one
  probe; 60 ticks trigger the next; `setNetTimeEnabled(true)` refreshes immediately and
  flips `netTimeEnabled`; `setCalendarIncluded` round-trips the empty-set-means-all rule
  through `CalendarChoice.included`; with a fake returning one busy interval today,
  `netTimeLabel` matches `^Net (\d+ h \d{2}|\d+ min)$`.
- **Native:** `./gradlew :core:runMacNative` → `** BUILD SUCCEEDED **` (this is also the
  first real K/N `platform.EventKit` interop in the project — the build succeeding is a
  meaningful check). Manual smoke test: enable Net time in Settings → Grant → macOS
  calendar prompt appears → calendars list fills in → with a busy event today, the
  `Net X h MM` line appears under the countdown in both windows and differs from the
  headline; declined/tentative/all-day/free events do not count; read with permission
  revoked in System Settings surfaces the error line.

## Risks

- **K/N EventKit interop is new ground for this repo** (dates via `NSDate` ↔ `Instant`,
  enum constants, completion handlers). Mitigation: the mapping mirrors a working Swift
  reference (`scripts/MacEventKitBridge.swift`) line for line; the build gate catches
  binding errors early.
- **Permission completion threading** — the completion runs on an arbitrary queue; it must
  hop to the main queue before touching the store or the controller (the session's
  single-thread invariant).
- **Empty-calendar regression** — guarded by the one-primed-store design; if the calendar
  list is empty after a grant, check store reuse first (see
  [[macos-ekeventstore-must-be-primed]]).
- **Tick-cadence drift** — refresh counts ticks, not wall time; a suspended laptop misses
  ticks, matching existing JVM minute-loop behaviour. Acceptable.

## Roadmap after this phase (context only)

7b: busy arcs on `DayRingCanvas` (main + mini) + hover event details — the snapshot gains a
primitives-only arc list from the controller's existing `busyBlockArcsState`. Then sounds,
presence/on-goal apps, sync, packaging/CI cutover, macOS Widget.
