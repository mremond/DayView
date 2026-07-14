# Mobile Engaged Time Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Android produce and persist the lenient "Engaged" figure by deriving it from the Pomodoro session window(s) at session close, so a mobile focus session no longer reports engaged = 0.

**Architecture:** A pure `deriveEngagedIntervals` computes engaged intervals from `[sessionStart … min(stopInstant, pomodoroEnd)]` minus declared detours; a pure `mergeIntervals` coalesces them. A controller capability flag `derivesEngagedFromSessions` (Android only) runs the derivation inside `closePomodoro`/`stopPomodoro` and persists the result through the shared `DayPreferences` snapshot. Desktop is untouched (per-tick accumulator + `DesktopPreferences`). The existing UI already gates each figure on `> Duration.ZERO`, so no UI change is needed.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, kotlinx.datetime `Instant`/`Duration`, AndroidX DataStore (preferences), kotlin.test.

## Global Constraints

- **Branch base:** This plan depends on PR #77 (branch `claude/focus-time-calculation-57422f`), which is **not yet merged**. Branch this work from `claude/focus-time-calculation-57422f`, or from `main` once #77 has landed — never from an older base.
- **Desktop behaviour must not change.** `derivesEngagedFromSessions` defaults to `false`; desktop keeps its per-tick accumulator and `DesktopPreferences` as the source of truth.
- **Lenient by design:** mobile subtracts declared `DetourEpisode`s only. Undeclared time away still counts as engaged.
- **Effective time only:** an interval ends at `min(stopInstant, pomodoroEnd)` — early close ends early, overtime is capped at `pomodoroEnd`.
- **No sync changes in this plan.** Cross-device merge is Plan B (`2026-07-14-engaged-time-cross-device-merge.md`).
- **ktlint is enforced.** Run `./gradlew ktlintFormat` before committing; the final gate is `./gradlew ktlintCheck :core:jvmTest :composeApp:testDebugUnitTest :composeApp:desktopTest`.
- **Commit messages:** English, describe the change only, no AI/Claude references, no test-plan section.

---

### Task 1: `mergeIntervals` — coalesce overlapping focus intervals

**Files:**
- Create: `core/src/commonMain/kotlin/fr/dayview/app/FocusIntervals.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/FocusIntervalsTest.kt`

**Interfaces:**
- Consumes: `FocusPresenceInterval(start: Instant, end: Instant)` from `PresenceAccumulator.kt`.
- Produces: `fun mergeIntervals(intervals: List<FocusPresenceInterval>): List<FocusPresenceInterval>` — sorted, disjoint (overlapping/adjacent runs merged). Empty input → empty output.

- [ ] **Step 1: Write the failing test**

Create `core/src/commonTest/kotlin/fr/dayview/app/FocusIntervalsTest.kt`:

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class FocusIntervalsTest {
    private fun at(sec: Long) = Instant.fromEpochMilliseconds(sec * 1000)
    private fun iv(s: Long, e: Long) = FocusPresenceInterval(at(s), at(e))

    @Test
    fun mergeIntervalsCoalescesOverlappingAndAdjacentRuns() {
        val merged = mergeIntervals(
            listOf(iv(30, 40), iv(0, 10), iv(8, 15), iv(15, 20)),
        )
        // 0-10 and 8-15 overlap -> 0-15; 15-20 is adjacent -> 0-20; 30-40 stays separate.
        assertEquals(listOf(iv(0, 20), iv(30, 40)), merged)
    }

    @Test
    fun mergeIntervalsIsIdempotent() {
        val once = mergeIntervals(listOf(iv(0, 10), iv(5, 20)))
        assertEquals(once, mergeIntervals(once))
    }

    @Test
    fun mergeIntervalsOnEmptyIsEmpty() {
        assertEquals(emptyList(), mergeIntervals(emptyList()))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.FocusIntervalsTest"`
Expected: FAIL — `mergeIntervals` unresolved reference.

- [ ] **Step 3: Write minimal implementation**

Create `core/src/commonMain/kotlin/fr/dayview/app/FocusIntervals.kt`:

```kotlin
package fr.dayview.app

/**
 * Coalesce a list of focus intervals into a sorted, disjoint set: overlapping or
 * touching runs are merged. Required before summing durations, since [focusedTime]
 * adds intervals without de-overlapping — unioning two devices' (or two sessions')
 * intervals otherwise double-counts.
 */
fun mergeIntervals(intervals: List<FocusPresenceInterval>): List<FocusPresenceInterval> {
    if (intervals.isEmpty()) return emptyList()
    val sorted = intervals.sortedBy { it.start }
    val merged = mutableListOf(sorted.first())
    for (next in sorted.drop(1)) {
        val last = merged.last()
        if (next.start <= last.end) {
            if (next.end > last.end) merged[merged.lastIndex] = last.copy(end = next.end)
        } else {
            merged.add(next)
        }
    }
    return merged
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.FocusIntervalsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add core/src/commonMain/kotlin/fr/dayview/app/FocusIntervals.kt core/src/commonTest/kotlin/fr/dayview/app/FocusIntervalsTest.kt
git commit -m "Add mergeIntervals to coalesce focus intervals"
```

---

### Task 2: `deriveEngagedIntervals` — session window minus detours

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/FocusIntervals.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/FocusIntervalsTest.kt`

**Interfaces:**
- Consumes: `FocusPresenceInterval`, `DetourEpisode(start, end, category, description)` from `Detours.kt`, `mergeIntervals` from Task 1.
- Produces: `fun deriveEngagedIntervals(sessionStart: Instant, effectiveEnd: Instant, detours: List<DetourEpisode>): List<FocusPresenceInterval>` — the window `[sessionStart, effectiveEnd]` with any overlapping detour spans removed, coalesced; empty if `effectiveEnd <= sessionStart` or the window is fully covered.

- [ ] **Step 1: Write the failing test**

Append to `core/src/commonTest/kotlin/fr/dayview/app/FocusIntervalsTest.kt`:

```kotlin
    private fun detour(s: Long, e: Long) = DetourEpisode(at(s), at(e), category = "x")

    @Test
    fun deriveEngagedWithNoDetoursIsTheWholeWindow() {
        assertEquals(listOf(iv(100, 1600)), deriveEngagedIntervals(at(100), at(1600), emptyList()))
    }

    @Test
    fun deriveEngagedSubtractsAnInteriorDetourIntoTwoPieces() {
        val pieces = deriveEngagedIntervals(at(0), at(1000), listOf(detour(400, 600)))
        assertEquals(listOf(iv(0, 400), iv(600, 1000)), pieces)
    }

    @Test
    fun deriveEngagedClampsDetoursToTheWindowEdges() {
        val pieces = deriveEngagedIntervals(at(100), at(1000), listOf(detour(0, 300), detour(900, 5000)))
        assertEquals(listOf(iv(300, 900)), pieces)
    }

    @Test
    fun deriveEngagedIsEmptyWhenWindowInvertedOrFullyCovered() {
        assertEquals(emptyList(), deriveEngagedIntervals(at(500), at(100), emptyList()))
        assertEquals(emptyList(), deriveEngagedIntervals(at(0), at(1000), listOf(detour(0, 1000))))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.FocusIntervalsTest"`
Expected: FAIL — `deriveEngagedIntervals` unresolved reference.

- [ ] **Step 3: Write minimal implementation**

Append to `core/src/commonMain/kotlin/fr/dayview/app/FocusIntervals.kt`:

```kotlin
/**
 * Engaged intervals for one Pomodoro session: the window [sessionStart, effectiveEnd]
 * with declared detours carved out. `effectiveEnd` is the caller's `min(stopInstant,
 * pomodoroEnd)`, so early stops end early and overtime is already capped. Returns 0..N
 * disjoint pieces (a detour in the middle splits the window).
 */
fun deriveEngagedIntervals(
    sessionStart: Instant,
    effectiveEnd: Instant,
    detours: List<DetourEpisode>,
): List<FocusPresenceInterval> {
    if (effectiveEnd <= sessionStart) return emptyList()
    // Detour spans clipped to the window, coalesced, walked left-to-right.
    val cuts = mergeIntervals(
        detours.mapNotNull {
            val s = maxOf(it.start, sessionStart)
            val e = minOf(it.end, effectiveEnd)
            if (e > s) FocusPresenceInterval(s, e) else null
        },
    )
    val pieces = mutableListOf<FocusPresenceInterval>()
    var cursor = sessionStart
    for (cut in cuts) {
        if (cut.start > cursor) pieces.add(FocusPresenceInterval(cursor, cut.start))
        cursor = maxOf(cursor, cut.end)
    }
    if (cursor < effectiveEnd) pieces.add(FocusPresenceInterval(cursor, effectiveEnd))
    return pieces
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.FocusIntervalsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add core/src/commonMain/kotlin/fr/dayview/app/FocusIntervals.kt core/src/commonTest/kotlin/fr/dayview/app/FocusIntervalsTest.kt
git commit -m "Derive engaged intervals from a session window minus detours"
```

---

### Task 3: Persist `focusSessionIntervals` + `focusSessionDayKey` in the shared snapshot

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt` (add two fields to `DayPreferencesSnapshot`)
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt` (keys, `persist`, `toSnapshot`)
- Test: `core/src/jvmTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt`

**Interfaces:**
- Consumes: `encodeFocusPresence` / `decodeFocusPresence` from `PresenceAccumulator.kt`, `FocusPresenceInterval`.
- Produces: `DayPreferencesSnapshot.focusSessionIntervals: List<FocusPresenceInterval>` (default `emptyList()`) and `DayPreferencesSnapshot.focusSessionDayKey: Long` (default `-1L`), round-tripped through the DataStore.

- [ ] **Step 1: Write the failing test**

Add to `core/src/jvmTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt` (follow the file's existing round-trip style; if it constructs a store via a temp DataStore helper, reuse that helper):

```kotlin
    @Test
    fun focusSessionIntervalsRoundTripThroughTheStore() = runTest {
        val store = newTempStore() // reuse the existing helper used by other tests in this file
        val intervals = listOf(
            FocusPresenceInterval(Instant.fromEpochMilliseconds(1_000), Instant.fromEpochMilliseconds(2_000)),
            FocusPresenceInterval(Instant.fromEpochMilliseconds(3_000), Instant.fromEpochMilliseconds(4_000)),
        )
        store.persist(DayPreferencesSnapshot(focusSessionDayKey = 20260, focusSessionIntervals = intervals))
        val read = store.snapshots.first()
        assertEquals(20260L, read.focusSessionDayKey)
        assertEquals(intervals, read.focusSessionIntervals)
    }
```

> If `DayPreferencesStoreTest.kt` has no `newTempStore()` helper, use the exact construction the other tests in that file already use to obtain a `DayPreferences` backed by a temp DataStore; do not invent a new helper name.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.DayPreferencesStoreTest"`
Expected: FAIL — `focusSessionDayKey` / `focusSessionIntervals` unresolved on `DayPreferencesSnapshot`.

- [ ] **Step 3a: Add the snapshot fields**

In `core/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt`, add to `DayPreferencesSnapshot` (next to `detoursDayKey` / `detours`):

```kotlin
    val focusSessionDayKey: Long = -1L,
    val focusSessionIntervals: List<FocusPresenceInterval> = emptyList(),
```

- [ ] **Step 3b: Add the DataStore keys and wiring**

In `core/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt`:

Add to `object DayPreferenceKeys` (near `DETOURS`):

```kotlin
    const val FOCUS_SESSION_DAY = "focus_session_day"
    const val FOCUS_SESSION = "focus_session"
```

Add the key declarations (near `detoursKey`):

```kotlin
private val focusSessionDayPrefKey = longPreferencesKey(DayPreferenceKeys.FOCUS_SESSION_DAY)
private val focusSessionKey = stringPreferencesKey(DayPreferenceKeys.FOCUS_SESSION)
```

In `persist(...)` (near `prefs[detoursKey] = encodeDetours(snapshot.detours)`):

```kotlin
            prefs[focusSessionDayPrefKey] = snapshot.focusSessionDayKey
            prefs[focusSessionKey] = encodeFocusPresence(snapshot.focusSessionIntervals)
```

In `Preferences.toSnapshot()` (near `detours = decodeDetours(...)`):

```kotlin
        focusSessionDayKey = this[focusSessionDayPrefKey] ?: defaults.focusSessionDayKey,
        focusSessionIntervals = decodeFocusPresence(this[focusSessionKey].orEmpty()),
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.DayPreferencesStoreTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add core/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt core/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt core/src/jvmTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt
git commit -m "Persist focus session intervals in the day preferences snapshot"
```

---

### Task 4: Controller — flag, session-close derivation, snapshot seeding

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt`

**Interfaces:**
- Consumes: `deriveEngagedIntervals`, `mergeIntervals` (Tasks 1–2); snapshot fields (Task 3); existing `dayKeyOf`, `state.detoursToday`, `state.pomodoroEnd`, `state.pomodoroMinutes`.
- Produces:
  - New ctor param `derivesEngagedFromSessions: Boolean = false`.
  - New `DayViewUiState.focusSessionDayKey: Long = -1L`.
  - `closePomodoro` and `stopPomodoro` append derived engaged intervals when the flag is set.
  - `toSnapshot`/`toUiState` carry `focusSessionIntervals` + `focusSessionDayKey`.

- [ ] **Step 1: Write the failing test**

Add to `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt` (reuse this file's existing controller-construction helper; pass `derivesEngagedFromSessions = true`):

```kotlin
    @Test
    fun androidStyleCloseRecordsEngagedTimeMinusDetours() = runTest {
        // Construct a controller with derivesEngagedFromSessions = true, a 60-minute
        // Pomodoro, using this file's existing helper/fixtures for now/day window.
        val controller = sessionController(derivesEngagedFromSessions = true, pomodoroMinutes = 60)
        controller.startPomodoro()                     // start at T0, end = T0 + 60m
        controller.advanceNow(minutes = 30)            // helper: moves state.now forward
        controller.closePomodoro(FocusClosureOutcome.PROGRESSED)  // early close at +30m
        // Engaged = 30 minutes (no detours), independent of strict focus.
        assertEquals(30.minutes, controller.stateFlow.value.let { sessionFocusedFrom(it) })
    }
```

> Use the helpers this test file already provides (controller factory, time advance, and how other tests read `sessionFocusedToday`). If a `derivesEngagedFromSessions`/`advanceNow`/`sessionFocusedFrom` helper does not exist, add the smallest one consistent with the file's existing fixtures — do not restructure the file. The assertion's intent: after an early close, engaged ≈ elapsed time.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.DayViewSessionTest"`
Expected: FAIL — `derivesEngagedFromSessions` is not a parameter / engaged stays 0.

- [ ] **Step 3a: Add the state field**

In `core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt`, add to `DayViewUiState` (next to `focusSessionIntervals`):

```kotlin
    val focusSessionDayKey: Long = -1L,
```

- [ ] **Step 3b: Add the ctor flag and seed logic**

Add the ctor parameter (after `initialFocusSessionIntervals`):

```kotlin
    private val derivesEngagedFromSessions: Boolean = false,
```

Change the `_stateFlow` initializer so the session intervals come from the snapshot when the flag is on (Android), and from the seed param when off (desktop):

```kotlin
    private val _stateFlow = MutableStateFlow(
        initialSnapshot.toUiState(initialNow).let { base ->
            base.copy(
                focusPresenceIntervals = initialFocusPresenceIntervals,
                focusSessionIntervals = if (derivesEngagedFromSessions) {
                    base.focusSessionIntervals
                } else {
                    initialFocusSessionIntervals
                },
            )
        },
    )
```

- [ ] **Step 3c: Add the derivation helper and call it**

Add a private helper (near `closePomodoro`):

```kotlin
    /**
     * Android path: derive this session's engaged intervals from its window and append
     * them (coalesced) to the day's list. `effectiveEnd` caps overtime at pomodoroEnd
     * and honours an early stop. No-op when the platform feeds engaged time per-tick.
     */
    private fun appendEngagedSession(stopInstant: Instant) {
        if (!derivesEngagedFromSessions) return
        val end = state.pomodoroEnd ?: return
        val start = end - state.pomodoroMinutes.minutes
        val effectiveEnd = minOf(stopInstant, end)
        val derived = deriveEngagedIntervals(start, effectiveEnd, state.detoursToday)
        if (derived.isEmpty()) return
        val today = dayKeyOf(state.now)
        val existing = if (state.focusSessionDayKey == today) state.focusSessionIntervals else emptyList()
        state = state.copy(
            focusSessionIntervals = mergeIntervals(existing + derived),
            focusSessionDayKey = today,
        )
    }
```

In `stopPomodoro()`, add the call before clearing:

```kotlin
    fun stopPomodoro() {
        appendEngagedSession(state.now)
        state = state.copy(pomodoroEnd = null)
        persistState()
    }
```

In `closePomodoro(outcome)`, add the call as the **first** line (before `closedFocusLedger` reads `state.pomodoroEnd`):

```kotlin
    fun closePomodoro(outcome: FocusClosureOutcome) {
        appendEngagedSession(state.now)
        val updatedIntention = focusIntentionAfterClosure(state.focusIntention, outcome)
        // ... unchanged ...
```

- [ ] **Step 3d: Carry the fields through snapshot mapping**

In `DayViewUiState.toSnapshot()` add (near `detours = detours,`):

```kotlin
    focusSessionDayKey = focusSessionDayKey,
    focusSessionIntervals = focusSessionIntervals,
```

In `DayPreferencesSnapshot.toUiState(now)` add (near `detours = safe.detours,`):

```kotlin
        focusSessionDayKey = safe.focusSessionDayKey,
        focusSessionIntervals = safe.focusSessionIntervals,
```

> Do **not** add these to `withPersisted(...)`: on Android the controller owns `focusSessionIntervals` and must not have it clobbered by an external snapshot echo. Also add `state.focusSessionDayKey` to the `persistedDayKey(...)` candidate list so an Android day with only focus data still archives:
> ```kotlin
> private fun persistedDayKey(state: DayViewUiState): Long? =
>     listOf(state.detoursDayKey, state.cleanSessions.dayKey, state.busyDayKey, state.focusSessionDayKey)
>         .filter { it != -1L }.maxOrNull()
> ```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.DayViewSessionTest" --tests "fr.dayview.app.FocusIntervalsTest"`
Expected: PASS. Then run the full core suite to catch regressions in the accumulator/history tests:
Run: `./gradlew :core:jvmTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt
git commit -m "Derive engaged time at session close on the mobile path"
```

---

### Task 5: Wire the flag on Android and verify the UI gating

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt` (add `derivesEngagedFromSessions` param; pass to controller; gate the session-intervals push)
- Modify: `composeApp/src/androidMain/kotlin/fr/dayview/app/MainActivity.kt` (pass `derivesEngagedFromSessions = true`)
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/` (desktop default-off smoke check, optional if an existing screen test already covers the gating)

**Interfaces:**
- Consumes: `DayViewController(derivesEngagedFromSessions = ...)` from Task 4.
- Produces: `DayViewApp(..., derivesEngagedFromSessions: Boolean = false)`.

- [ ] **Step 1: Add the App parameter and thread it to the controller**

In `App.kt`, add to the `App`/`DayViewApp` parameter list (near `focusSessionIntervals`):

```kotlin
    derivesEngagedFromSessions: Boolean = false,
```

Pass it into the `remember { DayViewController(...) }` construction (near `initialFocusSessionIntervals = focusSessionIntervals,`):

```kotlin
                            derivesEngagedFromSessions = derivesEngagedFromSessions,
```

- [ ] **Step 2: Gate the session-intervals push**

The existing effect would overwrite the controller-computed value with the (empty) parameter on Android. Wrap it:

```kotlin
                    LaunchedEffect(focusSessionIntervals, derivesEngagedFromSessions) {
                        if (!derivesEngagedFromSessions) {
                            controller.setFocusSessionIntervals(focusSessionIntervals)
                        }
                    }
```

Leave the `focusPresenceIntervals` effect unchanged.

- [ ] **Step 3: Turn the flag on for Android**

In `MainActivity.kt`, in the `DayViewApp(...)` call, add:

```kotlin
                derivesEngagedFromSessions = true,
```

- [ ] **Step 4: Verify UI gating needs no change**

Confirm (read-only) that `DayViewTodayScreen.kt` renders the engaged line only under `if (sessionFocusedToday > Duration.ZERO)` and the focus line only under `if (focusedToday > Duration.ZERO)` (both already present around lines 1298–1355). On Android `focusedToday` is `0`, so the Focus line stays hidden and only Engaged shows — no code change required. Note this in the commit body.

- [ ] **Step 5: Build both platforms and run their test suites**

Run: `./gradlew :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: PASS (desktop unchanged; Android compiles with the flag).

- [ ] **Step 6: Commit**

```bash
./gradlew ktlintFormat
git add composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt composeApp/src/androidMain/kotlin/fr/dayview/app/MainActivity.kt
git commit -m "Enable session-derived engaged time on Android"
```

---

### Task 6: Full gate

- [ ] **Step 1: Run the complete gate**

Run: `./gradlew ktlintCheck :core:jvmTest :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, no ktlint errors, no test failures, no stderr.

- [ ] **Step 2: Manual smoke (device or emulator)**

Run a short Pomodoro on Android, close it early, and confirm the "Engaged" figure appears on the Today screen and survives an app restart (persistence). Confirm no "Focus" line shows (strict is desktop-only).

- [ ] **Step 3: Commit any lint fixups**

```bash
git add -A
git commit -m "Tidy engaged-time mobile wiring" # only if ktlintFormat changed files
```

## Self-Review Notes (author)

- **Spec coverage:** derivation (§1) → Tasks 1–2; wiring + persistence (§2) → Tasks 3–4; UI data-driven visibility (§5) → already satisfied by PR #77, verified in Task 5 Step 4. Cross-device merge (§3–4) is deliberately **out of scope** here → Plan B.
- **Type consistency:** `deriveEngagedIntervals(sessionStart, effectiveEnd, detours)`, `mergeIntervals(intervals)`, `focusSessionDayKey: Long`, `derivesEngagedFromSessions: Boolean` are used identically across tasks.
- **Desktop safety:** flag defaults `false`; `appendEngagedSession` early-returns; session-intervals push preserved when off; new snapshot fields written but not read back on desktop.
