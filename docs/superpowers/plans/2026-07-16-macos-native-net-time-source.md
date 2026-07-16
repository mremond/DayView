# macOS Native EventKit Source + Net-Time Readout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Net time in the native macOS app, end-to-end: a Kotlin/Native EventKit calendar source, session polling into the existing controller ingestion, the `Net X h MM` readout under the countdown, and a Settings "Net time" section (toggle, permission grant, calendar checklist).

**Architecture:** A shared `probeNetTime` helper (commonMain, fake-testable) reproduces `App.kt`'s probe semantics (Task 1). `DayViewSession` gains a `CalendarSource` parameter, refresh triggers (creation / 60th tick / settings change / post-grant), three actions, and five snapshot fields incl. `netTimeLabel` and the `CalendarChoice` checklist (Task 2). `EventKitCalendarSource` implements the interface in `:core` macosMain over one primed `EKEventStore`, wired in `DayViewNative` (Task 3). Swift renders the new section and lines (Task 4).

**Tech Stack:** Kotlin Multiplatform (`:core` commonMain + macosMain, `platform.EventKit` interop), SwiftUI (macOS 15+), XcodeGen.

## Global Constraints

- NO controller logic changes — ingestion is the existing `updateNetTimeData(hasPermission, busyIntervals, availableCalendars, readError)`; settings via the existing `setNetTimeSettings`.
- Filtering contract (mirrors `scripts/MacEventKitBridge.swift` exactly, including the 2026-07-16 travel-time addition): skip `isAllDay`; skip `availability != busy`; skip events where the current-user attendee's participant status is declined or tentative; extend each busy interval upstream by the event's travel time (private KVC `"travelTime"`, `respondsToSelector`-guarded, non-finite rejected, clamped to `0..3h`), widen the fetch window by 3 h past `windowEnd`, and drop events whose extended start is still `>= windowEnd`.
- **One `EKEventStore` for the process lifetime**, created once and reused for the permission request and every read (a fresh store returns empty data even when authorized).
- All EventKit access on the main thread (the session's dispatcher); the permission completion hops to the main queue before invoking callbacks.
- `netTimeLabel` = `"Net " + formatDurationHm(netTime.netRemaining)` when net time is enabled and `netTime != null`, else `""` (e.g. "Net 4 h 12" / "Net 45 min").
- The settings checklist binds the RAW `state.availableCalendars` (the JVM settings list), NOT the day-gated `availableCalendarsToday`. `CalendarChoice.included` is EFFECTIVE inclusion: the id is in the set, or the set is empty (= all). Toggling goes through the existing `nextIncludedCalendars` (renormalizes all-included back to the empty set).
- Refresh cadence: once at session creation, every 60th `tick()`, immediately after `setNetTimeEnabled`/`setCalendarIncluded`, and on the post-grant callback.
- Native UI copy hardcoded English. Kotlin lint: `./gradlew ktlintCheck` must pass.
- Commit messages: English, imperative, change-only; no Claude/Anthropic/AI references. Commits succeed unsigned.
- Headless GUI clicking is blocked — interactive checks (the TCC prompt above all) are a manual smoke test; report exactly what was and wasn't verified.
- **Before Task 1:** create the working branch: `git checkout -b claude/macos-native-net-time`.

## File map

- Create: `core/src/commonMain/kotlin/fr/dayview/app/NetTimeProbe.kt` — `NetTimeProbeResult` + `probeNetTime`.
- Test: `core/src/commonTest/kotlin/fr/dayview/app/NetTimeProbeTest.kt` — new file, fake source.
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt` — `CalendarChoice` + 5 fields.
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt` — source param, refresh, 3 actions.
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt` — 4 new tests.
- Create: `core/src/macosMain/kotlin/fr/dayview/app/EventKitCalendarSource.kt` — the K/N source.
- Modify: `core/src/macosMain/kotlin/fr/dayview/app/DayViewNative.kt` — construct + wire the source.
- Modify: `macos/project.yml` — the calendar usage-description Info.plist key.
- Modify: `macos/DayView/TodayModel.swift`, `macos/DayView/SettingsView.swift`, `macos/DayView/RingView.swift`, `macos/DayView/MiniView.swift`.

---

## Task 1: `probeNetTime` shared helper (TDD)

**Files:**
- Create: `core/src/commonMain/kotlin/fr/dayview/app/NetTimeProbe.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/NetTimeProbeTest.kt`

**Interfaces:**
- Consumes: the existing `CalendarSource` interface, `BusyInterval`, `CalendarInfo` (all in `fr.dayview.app`).
- Produces: `NetTimeProbeResult(permission, busy, calendars, readError)` and `probeNetTime(source, enabled, includedCalendarIds, windowStart, windowEnd): NetTimeProbeResult` — Task 2's session refresh calls it.

- [ ] **Step 1: Write the failing tests**

Create `core/src/commonTest/kotlin/fr/dayview/app/NetTimeProbeTest.kt`:

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class NetTimeProbeTest {
    private class FakeCalendarSource(
        var permission: Boolean = true,
        var calendars: List<CalendarInfo> = emptyList(),
        var busy: List<BusyInterval> = emptyList(),
        var throwOnBusy: Boolean = false,
    ) : CalendarSource {
        var permissionChecks = 0
        var busyReads = 0

        override fun isSupported() = true

        override fun hasPermission(): Boolean {
            permissionChecks++
            return permission
        }

        override fun requestPermission() = Unit

        override fun availableCalendars(): List<CalendarInfo> = calendars

        override fun busyIntervals(
            windowStart: Instant,
            windowEnd: Instant,
            includedCalendarIds: Set<String>,
        ): List<BusyInterval> {
            busyReads++
            if (throwOnBusy) error("calendar read failed")
            return busy
        }
    }

    private val windowStart = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val windowEnd = Instant.fromEpochMilliseconds(1_700_030_000_000L)

    @Test
    fun disabledProbeSkipsTheSourceEntirely() {
        val source = FakeCalendarSource()
        val result = probeNetTime(source, enabled = false, includedCalendarIds = emptySet(), windowStart, windowEnd)
        assertEquals(NetTimeProbeResult(permission = false), result)
        assertEquals(0, source.permissionChecks)
        assertEquals(0, source.busyReads)
    }

    @Test
    fun enabledWithoutPermissionReportsNoPermission() {
        val source = FakeCalendarSource(permission = false)
        val result = probeNetTime(source, enabled = true, includedCalendarIds = emptySet(), windowStart, windowEnd)
        assertEquals(NetTimeProbeResult(permission = false), result)
        assertEquals(0, source.busyReads)
    }

    @Test
    fun happyPathPassesIntervalsAndCalendarsThrough() {
        val busy = listOf(BusyInterval(windowStart, windowEnd, titles = listOf("Standup"), calendarId = "c1"))
        val calendars = listOf(CalendarInfo("c1", "Work"))
        val source = FakeCalendarSource(busy = busy, calendars = calendars)
        val result = probeNetTime(source, enabled = true, includedCalendarIds = emptySet(), windowStart, windowEnd)
        assertEquals(NetTimeProbeResult(permission = true, busy = busy, calendars = calendars), result)
    }

    @Test
    fun throwingBusyReadSurfacesAsReadErrorWithCalendarsIntact() {
        val calendars = listOf(CalendarInfo("c1", "Work"))
        val source = FakeCalendarSource(calendars = calendars, throwOnBusy = true)
        val result = probeNetTime(source, enabled = true, includedCalendarIds = emptySet(), windowStart, windowEnd)
        assertEquals(
            NetTimeProbeResult(permission = true, busy = emptyList(), calendars = calendars, readError = true),
            result,
        )
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.NetTimeProbeTest'`
Expected: FAIL to compile — `unresolved reference: probeNetTime` / `NetTimeProbeResult`.

- [ ] **Step 3: Implement the helper**

Create `core/src/commonMain/kotlin/fr/dayview/app/NetTimeProbe.kt`:

```kotlin
package fr.dayview.app

import kotlin.time.Instant

/** Result of one calendar probe; mirrors what DayViewController.updateNetTimeData ingests. */
data class NetTimeProbeResult(
    val permission: Boolean,
    val busy: List<BusyInterval> = emptyList(),
    val calendars: List<CalendarInfo> = emptyList(),
    val readError: Boolean = false,
)

/**
 * Reads the busy layer for one day window. Same semantics as the desktop App's inline
 * probe: disabled means the source is never touched; a thrown interval read surfaces as
 * [NetTimeProbeResult.readError] (distinct from "no events") with the calendar list still
 * read; never throws.
 */
fun probeNetTime(
    source: CalendarSource,
    enabled: Boolean,
    includedCalendarIds: Set<String>,
    windowStart: Instant,
    windowEnd: Instant,
): NetTimeProbeResult {
    if (!enabled) return NetTimeProbeResult(permission = false)
    val granted = runCatching { source.hasPermission() }.getOrDefault(false)
    if (!granted) return NetTimeProbeResult(permission = false)
    val intervals = runCatching { source.busyIntervals(windowStart, windowEnd, includedCalendarIds) }
    val calendars = runCatching { source.availableCalendars() }.getOrDefault(emptyList())
    return NetTimeProbeResult(
        permission = true,
        busy = intervals.getOrDefault(emptyList()),
        calendars = calendars,
        readError = intervals.isFailure,
    )
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.NetTimeProbeTest'`
Expected: PASS (4/4).

- [ ] **Step 5: Lint**

Run: `./gradlew ktlintCheck`
Expected: BUILD SUCCESSFUL (use `ktlintFormat` if flagged).

- [ ] **Step 6: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/NetTimeProbe.kt core/src/commonTest/kotlin/fr/dayview/app/NetTimeProbeTest.kt
git commit -m "feat(core): shared net-time calendar probe helper"
```

---

## Task 2: Session wiring, snapshot fields, and actions (TDD)

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt`
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt`

**Interfaces:**
- Consumes: `probeNetTime`/`NetTimeProbeResult` (Task 1); existing `controller.updateNetTimeData`, `controller.setNetTimeSettings`, `nextIncludedCalendars`, `dayWindow`, `formatDurationHm`; state vals `netTimeSettings`, `availableCalendars`, `netCalendarPermission`, `netCalendarError`, `netTime`.
- Produces (Task 4 relies on these): snapshot fields `netTimeEnabled: Boolean`, `calendarPermission: Boolean`, `calendarReadError: Boolean`, `netTimeLabel: String`, `calendars: List<CalendarChoice>`; type `CalendarChoice(id: String, displayName: String, included: Boolean)`; session methods `setNetTimeEnabled(enabled: Boolean)`, `setCalendarIncluded(id: String, included: Boolean)`, `requestCalendarAccess()`, `refreshCalendar()`; constructor param `calendarSource: CalendarSource = NoopCalendarSource` (Task 3 passes the real source).

- [ ] **Step 1: Write the failing tests**

Append inside the `DayViewSessionTest` class in `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt`. Add `import kotlin.time.Duration.Companion.hours` to the imports (the rest are present). The `FakeCalendarSource` here is intentionally local to this file (the Task 1 fake asserts probe mechanics; this one drives the session):

```kotlin
    private class FakeCalendarSource(
        var permission: Boolean = true,
        var calendars: List<CalendarInfo> = emptyList(),
        var busy: List<BusyInterval> = emptyList(),
    ) : CalendarSource {
        var busyReads = 0

        override fun isSupported() = true

        override fun hasPermission() = permission

        override fun requestPermission() = Unit

        override fun availableCalendars(): List<CalendarInfo> = calendars

        override fun busyIntervals(
            windowStart: Instant,
            windowEnd: Instant,
            includedCalendarIds: Set<String>,
        ): List<BusyInterval> {
            busyReads++
            return busy
        }
    }

    @Test
    fun creationProbesTheCalendarAndFillsTheSnapshot() = runTest {
        val now = Instant.fromEpochMilliseconds(1_699_956_000_000L) // midday UTC fixture
        val source = FakeCalendarSource(
            calendars = listOf(CalendarInfo("c1", "Work")),
            busy = listOf(BusyInterval(now, now + 1.hours, titles = listOf("Standup"), calendarId = "c1")),
        )
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(
                startMinutes = 0,
                endMinutes = 1439,
                netTimeSettings = NetTimeSettings(enabled = true),
            ),
            initialNow = now,
        )
        val session = DayViewSession(controller, backgroundScope, source)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        runCurrent()

        assertEquals(true, seen.last().netTimeEnabled)
        assertEquals(true, seen.last().calendarPermission)
        assertEquals(false, seen.last().calendarReadError)
        assertTrue(Regex("^Net (\\d+ h \\d{2}|\\d+ min)$").matches(seen.last().netTimeLabel))
        assertEquals(listOf(CalendarChoice("c1", "Work", included = true)), seen.last().calendars)

        sub.cancel()
    }

    @Test
    fun tickRefreshesTheCalendarEverySixtiethTick() = runTest {
        val source = FakeCalendarSource()
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(
                startMinutes = 0,
                endMinutes = 1439,
                netTimeSettings = NetTimeSettings(enabled = true),
            ),
            initialNow = Instant.fromEpochMilliseconds(1_699_956_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope, source)
        runCurrent()
        assertEquals(1, source.busyReads) // the creation probe

        repeat(59) { session.tick() }
        assertEquals(1, source.busyReads)

        session.tick() // 60th
        assertEquals(2, source.busyReads)
    }

    @Test
    fun setNetTimeEnabledRefreshesImmediately() = runTest {
        val source = FakeCalendarSource(calendars = listOf(CalendarInfo("c1", "Work")))
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = Instant.fromEpochMilliseconds(1_699_956_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope, source)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        runCurrent()
        assertEquals(false, seen.last().netTimeEnabled)
        assertEquals(false, seen.last().calendarPermission) // disabled probe never asks

        session.setNetTimeEnabled(true)
        runCurrent()
        assertEquals(true, seen.last().netTimeEnabled)
        assertEquals(true, seen.last().calendarPermission)

        sub.cancel()
    }

    @Test
    fun calendarInclusionRoundTripsTheEmptySetMeansAllRule() = runTest {
        val source = FakeCalendarSource(
            calendars = listOf(CalendarInfo("c1", "Work"), CalendarInfo("c2", "Home")),
        )
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(
                startMinutes = 0,
                endMinutes = 1439,
                netTimeSettings = NetTimeSettings(enabled = true),
            ),
            initialNow = Instant.fromEpochMilliseconds(1_699_956_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope, source)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        runCurrent()
        // Empty set = all included.
        assertEquals(listOf(true, true), seen.last().calendars.map { it.included })

        session.setCalendarIncluded("c2", included = false)
        runCurrent()
        assertEquals(
            listOf(CalendarChoice("c1", "Work", true), CalendarChoice("c2", "Home", false)),
            seen.last().calendars,
        )

        // Re-including everything renormalizes back to the empty set (= all).
        session.setCalendarIncluded("c2", included = true)
        runCurrent()
        assertEquals(listOf(true, true), seen.last().calendars.map { it.included })

        sub.cancel()
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.DayViewSessionTest'`
Expected: FAIL to compile — `unresolved reference` for the new fields/methods.

- [ ] **Step 3: Add `CalendarChoice` and the snapshot fields**

In `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt`:

1. Add after the `TodaySnapshot` data class (top level):

```kotlin
/** One row of the settings calendar checklist. [included] is the EFFECTIVE inclusion. */
data class CalendarChoice(
    val id: String,
    val displayName: String,
    val included: Boolean,
)
```

2. Add to the END of the `TodaySnapshot` constructor parameter list (after `menuBarTitle: String,`):

```kotlin
    val netTimeEnabled: Boolean,
    val calendarPermission: Boolean,
    val calendarReadError: Boolean,
    val netTimeLabel: String, // "Net " + formatDurationHm(netRemaining), "" when off/no data
    val calendars: List<CalendarChoice>, // raw available calendars (settings checklist)
```

3. Add to the END of the `TodaySnapshot(...)` construction inside `toTodaySnapshot` (after `menuBarTitle = ...,`):

```kotlin
        netTimeEnabled = netTimeSettings.enabled,
        calendarPermission = netCalendarPermission,
        calendarReadError = netCalendarError,
        netTimeLabel = netTime?.let { "Net ${formatDurationHm(it.netRemaining)}" } ?: "",
        calendars = availableCalendars.map { cal ->
            CalendarChoice(
                id = cal.id,
                displayName = cal.displayName,
                included = netTimeSettings.includedCalendarIds.isEmpty() ||
                    cal.id in netTimeSettings.includedCalendarIds,
            )
        },
```

(`netTime` is null unless net time is enabled, so the label is `""` when disabled. `availableCalendars` is the raw state list — deliberately NOT `availableCalendarsToday`.)

- [ ] **Step 4: Wire the session**

In `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt`:

1. Change the class header to take the source:

```kotlin
class DayViewSession internal constructor(
    private val controller: DayViewController,
    private val scope: CoroutineScope,
    private val calendarSource: CalendarSource = NoopCalendarSource,
) {
    private var ticksSinceCalendarRefresh = 0

    init {
        refreshCalendar()
    }
```

2. Replace the existing single-line `fun tick() = controller.tick(Clock.System.now())` with:

```kotlin
    fun tick() {
        controller.tick(Clock.System.now())
        // JVM cadence: re-probe the calendar once a minute.
        if (++ticksSinceCalendarRefresh >= 60) {
            ticksSinceCalendarRefresh = 0
            refreshCalendar()
        }
    }
```

3. Add after the `setThemeMode` method:

```kotlin
    /** Re-reads the busy layer and injects it. Public: the post-grant callback uses it. */
    fun refreshCalendar() {
        val state = controller.stateFlow.value
        val (start, end) = dayWindow(state.now, state.startMinutes, state.endMinutes)
        val probe = probeNetTime(
            source = calendarSource,
            enabled = state.netTimeSettings.enabled,
            includedCalendarIds = state.netTimeSettings.includedCalendarIds,
            windowStart = start,
            windowEnd = end,
        )
        controller.updateNetTimeData(probe.permission, probe.busy, probe.calendars, probe.readError)
    }

    fun setNetTimeEnabled(enabled: Boolean) {
        val settings = controller.stateFlow.value.netTimeSettings
        controller.setNetTimeSettings(settings.copy(enabled = enabled))
        refreshCalendar()
    }

    fun setCalendarIncluded(id: String, included: Boolean) {
        val state = controller.stateFlow.value
        val settings = state.netTimeSettings
        val allIds = state.availableCalendars.map { it.id }
        controller.setNetTimeSettings(
            settings.copy(
                includedCalendarIds = nextIncludedCalendars(allIds, settings.includedCalendarIds, id, included),
            ),
        )
        refreshCalendar()
    }

    fun requestCalendarAccess() = calendarSource.requestPermission()
```

(No new imports beyond what the file has: `CalendarSource`/`NoopCalendarSource`/`probeNetTime`/`dayWindow`/`nextIncludedCalendars` are all in `fr.dayview.app`.)

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.DayViewSessionTest'`
Expected: PASS (all tests, including the four new ones; the pre-existing ones stand because the source parameter defaults to `NoopCalendarSource`).

- [ ] **Step 6: Lint, then run the full core suite**

Run: `./gradlew ktlintCheck :core:jvmTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt
git commit -m "feat(core): calendar-aware session with net-time snapshot fields and actions"
```

---

## Task 3: `EventKitCalendarSource` (Kotlin/Native) + wiring + usage string

**Files:**
- Create: `core/src/macosMain/kotlin/fr/dayview/app/EventKitCalendarSource.kt`
- Modify: `core/src/macosMain/kotlin/fr/dayview/app/DayViewNative.kt`
- Modify: `macos/project.yml`

**Interfaces:**
- Consumes: `CalendarSource`, `CalendarInfo`, `BusyInterval` (commonMain); `DayViewSession(controller, scope, calendarSource)` from Task 2; `session.refreshCalendar()`.
- Produces: `EventKitCalendarSource` with a settable `onPermissionChange: (() -> Unit)?`.

- [ ] **Step 1: Implement the source**

Create `core/src/macosMain/kotlin/fr/dayview/app/EventKitCalendarSource.kt`:

```kotlin
package fr.dayview.app

import platform.EventKit.EKAuthorizationStatusFullAccess
import platform.EventKit.EKCalendar
import platform.EventKit.EKEntityType
import platform.EventKit.EKEvent
import platform.EventKit.EKEventAvailabilityBusy
import platform.EventKit.EKEventStore
import platform.EventKit.EKParticipant
import platform.EventKit.EKParticipantStatusDeclined
import platform.EventKit.EKParticipantStatusTentative
import platform.Foundation.NSDate
import platform.Foundation.NSNumber
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.objc.sel_registerName
import kotlin.time.Instant

/**
 * EventKit-backed calendar reader for the native macOS app.
 *
 * ONE EKEventStore is created for the process lifetime and reused for the permission
 * request and every read — a fresh store returns empty calendars/events even when
 * authorized (the "unprimed store" bug the JVM bridge hit). Filtering mirrors
 * scripts/MacEventKitBridge.swift: busy-availability only, no all-day events, no events
 * the current user declined or marked tentative, and busy intervals extended upstream by
 * the event's travel time (private KVC key, guarded and clamped like the JVM bridge).
 *
 * Main-thread only: the session scope runs on the main dispatcher, and the permission
 * completion hops back to the main queue before invoking [onPermissionChange].
 */
class EventKitCalendarSource : CalendarSource {
    private val store = EKEventStore()

    // EventKit has no public travel-time API; see MacEventKitBridge.swift for the
    // rationale of the KVC access, the finite check, and the 3 h clamp.
    private val maxTravelSeconds = 3.0 * 60 * 60

    /** Invoked on the main queue after the user answers the access prompt. */
    var onPermissionChange: (() -> Unit)? = null

    override fun isSupported(): Boolean = true

    override fun hasPermission(): Boolean =
        EKEventStore.authorizationStatusForEntityType(EKEntityType.EKEntityTypeEvent) ==
            EKAuthorizationStatusFullAccess

    override fun requestPermission() {
        store.requestFullAccessToEventsWithCompletion { _, _ ->
            dispatch_async(dispatch_get_main_queue()) {
                onPermissionChange?.invoke()
            }
        }
    }

    override fun availableCalendars(): List<CalendarInfo> =
        eventCalendars().map { CalendarInfo(id = it.calendarIdentifier, displayName = it.title) }

    override fun busyIntervals(
        windowStart: Instant,
        windowEnd: Instant,
        includedCalendarIds: Set<String>,
    ): List<BusyInterval> {
        // Empty set = all calendars (predicate takes null); a non-empty set that matches
        // no calendars reads as no events.
        val calendars = if (includedCalendarIds.isEmpty()) {
            null
        } else {
            eventCalendars().filter { it.calendarIdentifier in includedCalendarIds }
                .ifEmpty { return emptyList() }
        }
        // Fetch up to maxTravelSeconds past the requested end so an event starting after
        // the window whose travel time overlaps it is still seen (mirrors the JVM bridge).
        val predicate = store.predicateForEventsWithStartDate(
            startDate = windowStart.toNSDate(),
            endDate = NSDate.dateWithTimeIntervalSince1970(
                windowEnd.toEpochMilliseconds() / 1000.0 + maxTravelSeconds,
            ),
            calendars = calendars,
        )
        return store.eventsMatchingPredicate(predicate)
            .orEmpty()
            .filterIsInstance<EKEvent>()
            .filterNot { it.allDay }
            .filter { it.availability == EKEventAvailabilityBusy }
            .filterNot { it.currentUserDeclinedOrTentative() }
            .mapNotNull { event ->
                val start = event.startDate ?: return@mapNotNull null
                val end = event.endDate ?: return@mapNotNull null
                // Travel time blocks the stretch before the event: extend upstream. Events
                // pulled in only by the widened fetch that still don't reach back into the
                // requested window are dropped.
                val busyStart = start.toInstant().toEpochMilliseconds() -
                    (travelSeconds(event) * 1000.0).toLong()
                if (busyStart >= windowEnd.toEpochMilliseconds()) return@mapNotNull null
                BusyInterval(
                    start = Instant.fromEpochMilliseconds(busyStart),
                    end = end.toInstant(),
                    titles = listOf(event.title ?: ""),
                    calendarId = event.calendar?.calendarIdentifier ?: "",
                )
            }
    }

    // EventKit has no public API for travel time; the private KVC key "travelTime"
    // (seconds) is the only access. respondsToSelector makes an OS release that removes
    // the accessor degrade to "no travel"; the finite check + clamp keep a corrupt value
    // from swallowing the day. Mirrors travelSeconds() in MacEventKitBridge.swift.
    private fun travelSeconds(event: EKEvent): Double {
        if (!event.respondsToSelector(sel_registerName("travelTime"))) return 0.0
        val travel = (event.valueForKey("travelTime") as? NSNumber)?.doubleValue ?: return 0.0
        if (!travel.isFinite()) return 0.0
        return travel.coerceIn(0.0, maxTravelSeconds)
    }

    private fun eventCalendars(): List<EKCalendar> =
        store.calendarsForEntityType(EKEntityType.EKEntityTypeEvent)
            .orEmpty()
            .filterIsInstance<EKCalendar>()

    private fun EKEvent.currentUserDeclinedOrTentative(): Boolean {
        val me = attendees.orEmpty().filterIsInstance<EKParticipant>().firstOrNull { it.currentUser }
            ?: return false
        return me.participantStatus == EKParticipantStatusDeclined ||
            me.participantStatus == EKParticipantStatusTentative
    }

    private fun Instant.toNSDate(): NSDate =
        NSDate.dateWithTimeIntervalSince1970(toEpochMilliseconds() / 1000.0)

    private fun NSDate.toInstant(): Instant =
        Instant.fromEpochMilliseconds((timeIntervalSince1970 * 1000.0).toLong())
}
```

**Binding-name adaptation is expected.** This is the repo's first `platform.EventKit` interop; the K/N import names may differ slightly from the code above. Compile (Step 3) and adapt names ONLY — the semantics (one store, the three filters, the empty-set-means-all predicate) are fixed by the spec. Known likely variants:
- `EKEntityType.EKEntityTypeEvent` may import as a top-level constant `EKEntityTypeEvent` (drop the qualifier), and class methods as `EKEventStore.Companion.authorizationStatusForEntityType(...)`.
- `it.allDay` may import as `it.isAllDay()`; `it.currentUser` on `EKParticipant` may import as `it.isCurrentUser()`.
- If `EKAuthorizationStatusFullAccess` is missing from the platform libs, use `EKAuthorizationStatusAuthorized` (same raw value, pre-14 name). If `requestFullAccessToEventsWithCompletion` is missing, use `requestAccessToEntityType(EKEntityType.EKEntityTypeEvent, completion)`.
- `sel_registerName` may live under `platform.objc` or need `NSSelectorFromString` (`platform.Foundation`); `valueForKey` is on `NSObject` and may need an explicit cast to `NSObject` first. `respondsToSelector` takes the selector pointer type the compiler names (`COpaquePointer?`/`SEL`).
- An `@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)` annotation on the class may be required.

- [ ] **Step 2: Wire it in `DayViewNative`**

Replace the `create()` body in `core/src/macosMain/kotlin/fr/dayview/app/DayViewNative.kt`:

```kotlin
    fun create(): DayViewSession {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val preferences = macosDayPreferences()
        val controller = DayViewController(
            preferences,
            scope,
            initialSnapshot = runBlocking { preferences.snapshots.first() },
        )
        val source = EventKitCalendarSource()
        val session = DayViewSession(controller, scope, source)
        // After the user answers the access prompt, re-read immediately instead of
        // waiting for the next minute tick.
        source.onPermissionChange = { session.refreshCalendar() }
        return session
    }
```

- [ ] **Step 3: Compile the native target and adapt binding names**

Run: `./gradlew :core:compileKotlinMacosArm64`
Expected: BUILD SUCCESSFUL, possibly after the name adaptations listed in Step 1. Then lint: `./gradlew ktlintCheck`.

- [ ] **Step 4: Add the usage description**

In `macos/project.yml`, in `targets.DayView.settings.base`, add this line (alongside the other `base` keys):

```yaml
        INFOPLIST_KEY_NSCalendarsFullAccessUsageDescription: DayView subtracts the time of your busy calendar events from the remaining day.
```

(Without this Info.plist key the macOS 14+ full-access request fails outright.)

- [ ] **Step 5: Full native build**

Run: `./gradlew :core:runMacNative`
Expected: `** BUILD SUCCEEDED **` and the app launches (behaviour unchanged — nothing consumes the new fields yet). Close it: `pkill -f 'macos/build/Build/Products/Debug/DayView.app' || true`.

- [ ] **Step 6: Commit**

```bash
git add core/src/macosMain/kotlin/fr/dayview/app/EventKitCalendarSource.kt core/src/macosMain/kotlin/fr/dayview/app/DayViewNative.kt macos/project.yml
git commit -m "feat(core): Kotlin/Native EventKit calendar source for the macOS app"
```

---

## Task 4: Settings "Net time" section + net readout in both windows

**Files:**
- Modify: `macos/DayView/TodayModel.swift`
- Modify: `macos/DayView/SettingsView.swift`
- Modify: `macos/DayView/RingView.swift`
- Modify: `macos/DayView/MiniView.swift`

**Interfaces:**
- Consumes: snapshot fields `netTimeEnabled`, `calendarPermission`, `calendarReadError`, `netTimeLabel`, `calendars` (`CalendarChoice` with `id`/`displayName`/`included`); session actions `setNetTimeEnabled(enabled:)`, `setCalendarIncluded(id:included:)`, `requestCalendarAccess()` (Kotlin `Boolean` → Swift `Bool`, `String` → `String`).

- [ ] **Step 1: `TodayModel` passthroughs**

In `macos/DayView/TodayModel.swift`, add after `func setThemeMode(_ mode: String) { ... }`:

```swift
    func setNetTimeEnabled(_ enabled: Bool) { session.setNetTimeEnabled(enabled: enabled) }
    func setCalendarIncluded(id: String, included: Bool) { session.setCalendarIncluded(id: id, included: included) }
    func requestCalendarAccess() { session.requestCalendarAccess() }
```

- [ ] **Step 2: The Settings section**

In `macos/DayView/SettingsView.swift`, add a third `Section` inside the `Form`, after the `Section("Display") { ... }` block:

```swift
            Section("Net time") {
                Toggle(
                    "Net time calculation",
                    isOn: Binding(
                        get: { model.snapshot.netTimeEnabled },
                        set: { model.setNetTimeEnabled($0) }
                    )
                )
                Text("Subtracts busy slots from your calendar and greys them out on the circle.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if model.snapshot.netTimeEnabled {
                    if !model.snapshot.calendarPermission {
                        Button("Grant calendar access") { model.requestCalendarAccess() }
                        Text("macOS will ask you to allow DayView to read your calendars.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else {
                        if model.snapshot.calendarReadError {
                            Text("Calendar could not be read.")
                                .font(.caption)
                                .foregroundStyle(.orange)
                        }
                        ForEach(model.snapshot.calendars, id: \.id) { calendar in
                            Toggle(
                                calendar.displayName,
                                isOn: Binding(
                                    get: { calendar.included },
                                    set: { model.setCalendarIncluded(id: calendar.id, included: $0) }
                                )
                            )
                        }
                        Text("Only events marked busy (excluding all-day events) are subtracted.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
```

(The controls keep the Phase 6 pattern: no local `@State`, get-from-snapshot / set-through-bridge. `calendars` arrives as a bridged array of `CalendarChoice`; `id: \.id` keys the rows.)

- [ ] **Step 3: The net line in both windows**

1. In `macos/DayView/RingView.swift`, in `ringSection`, add after the `if !model.snapshot.secondsLabel.isEmpty { ... }` block:

```swift
            if !model.snapshot.netTimeLabel.isEmpty {
                Text(model.snapshot.netTimeLabel)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
```

2. In `macos/DayView/MiniView.swift`, add the same block after its `if !model.snapshot.secondsLabel.isEmpty { ... }` block:

```swift
                        if !model.snapshot.netTimeLabel.isEmpty {
                            Text(model.snapshot.netTimeLabel)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .monospacedDigit()
                        }
```

- [ ] **Step 4: Build and launch**

Run: `./gradlew :core:runMacNative`
Expected: `** BUILD SUCCEEDED **`; the app launches; Settings (⌘,) shows the "Net time" section with the toggle off by default; the windows are unchanged while net time is off.

- [ ] **Step 5: Verify what the environment allows, report the rest as manual**

Confirm automatically: build, launch, process alive (`pgrep -f 'Debug/DayView.app'`). The rest is the **manual smoke test** (GUI + the TCC prompt cannot be driven in-sandbox) — report as not-verified:

1. Settings → enable "Net time calculation" → "Grant calendar access" appears → clicking it raises the macOS calendar prompt (the usage string from Task 3 shows).
2. After granting: the calendar checklist fills in; toggling a calendar off excludes its events.
3. With a busy (non-all-day, accepted) event today: the `Net X h MM` line appears under the countdown in both windows and is smaller than the headline; declined/tentative/free/all-day events do not change it.
4. Revoking calendar access in System Settings while net time is on surfaces the grant button again on the next refresh (≤1 minute).
5. Disabling net time removes the line.
6. (If convenient) an event with travel time in Apple Calendar reduces the net figure by the travel stretch too — parity with the JVM app's 2026-07-16 travel-time deduction.

Close afterward: `pkill -f 'macos/build/Build/Products/Debug/DayView.app' || true`.

- [ ] **Step 6: Commit**

```bash
git add macos/DayView/TodayModel.swift macos/DayView/SettingsView.swift macos/DayView/RingView.swift macos/DayView/MiniView.swift
git commit -m "feat(macos): net-time settings section and readout in both windows"
```

---

## Self-Review Notes

- **Spec coverage:** `probeNetTime` helper + semantics → Task 1 (4 tests incl. the readError/no-events distinction and disabled-never-touches-source); session param/refresh triggers/actions + 5 snapshot fields + `CalendarChoice` effective inclusion → Task 2 (4 tests: creation probe, 60-tick cadence, immediate refresh on enable, empty-set-means-all round trip incl. renormalization); one primed `EKEventStore` + the three filters + main-thread rule + post-grant callback + usage-description key → Task 3; settings section copy/structure + net line in both windows → Task 4; the manual smoke list → Task 4 Step 5. Packaging entitlement is spec-recorded as future work — deliberately no task.
- **Type consistency:** `DayViewSession(controller, scope, calendarSource)` matches Task 3's wiring; `refreshCalendar()` public and used by the callback; `CalendarChoice(id, displayName, included)` matches the Swift `\.id`/`displayName`/`included` usage; Kotlin `setCalendarIncluded(id: String, included: Boolean)` surfaces as `setCalendarIncluded(id:included:)`; the label regex matches `formatDurationHm`'s actual "H h MM"/"MM min" output.
- **No placeholders:** every step carries complete code; Task 3 Step 1's adaptation note bounds permitted changes to binding NAMES with the semantics pinned.
- **YAGNI:** no arcs (7b), no observeChanges, no upcoming-days data, no JVM probe adoption, no background queue.
- **Known nuance:** `tick()` counts ticks, not wall time — a suspended laptop delays the refresh to 60 fresh ticks, matching JVM minute-loop behaviour per the spec.
