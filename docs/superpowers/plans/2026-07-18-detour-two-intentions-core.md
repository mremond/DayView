# Detours — two intentions: `:core` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the model half of the two-detour-intentions design in `:core` — the anchored
default start, the motif-free open detour with a motif-at-stop close, and the hollowing of
engaged time on both engaged-time paths — with no UI change on any platform.

**Architecture:** A pure function (`detourAnchorStart`) in `Detours.kt` computes case 1's
default start. `DayViewController` loses two guards on `startOpenDetour` and gains a
`stopOpenDetour(category, description)` / `cancelOpenDetour()` pair that caps and floors the
committed span. Engaged time is hollowed on both paths: the session-derived path
(`appendEngagedSession`, Android) carves with a provisional episode, and the per-tick presence
path (`PresenceCoordinator.observe`, macOS/desktop) forces the tick off-goal while a detour is
open.

**Tech Stack:** Kotlin Multiplatform, `kotlinx-datetime` (`kotlin.time.Instant`), `kotlin.test`,
Gradle. Tests in `core/src/commonTest`, run on the JVM target.

## Global Constraints

- Design source of truth: `docs/superpowers/specs/2026-07-18-detour-two-intentions-design.md`.
- Anchor lookback cap: **120 minutes**. Open-detour span cap: **4 hours**, additionally floored
  at the start of the local day so a span can never cross midnight.
- This plan touches `:core` only. No `:shared`, `:androidApp`, or `macos/` file is modified.
  Compose and Swift call sites keep compiling because no existing public signature is removed
  without its replacement being added in the same task.
- ktlint is enforced. Run `./gradlew ktlintCheck` before every commit; `./gradlew ktlintFormat`
  auto-fixes.
- Full gate before the last commit:
  `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`
- Commit messages in English. Never add a Claude/Anthropic/AI reference, a `Co-Authored-By`
  trailer, or a "Generated with" footer. Do not reference `docs/superpowers/` in commit
  messages.
- Detours stay purely informational — no task may change the countdown or net time.

---

### Task 1: The anchored default start

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/Detours.kt` (add after
  `detourDefaultStartMinutes`, around line 268)
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DetoursTest.kt`

**Interfaces:**
- Consumes: `DetourEpisode` (`start`, `end`), `FocusSessionRecord` (`start`, `end`,
  `intention`, `outcome`) — both already in `:core`.
- Produces: `detourAnchorStart(now, detours, focusSessions, windowStart, maxLookback): Instant`.
  Task 2 does **not** use it; the UI plans (macOS, Compose) do.

`detourDefaultStartMinutes` stays in place for now — the Compose capture dialog still calls it
(`DetoursUi.kt:204`). It is retired in the Compose UI plan, not here.

- [ ] **Step 1: Write the failing tests**

Append to `core/src/commonTest/kotlin/fr/dayview/app/DetoursTest.kt`, inside `class DetoursTest`:

```kotlin
    // Anchor tests work in whole minutes from a fixed "now" for readability.
    private val anchorNow = t(1_700_000_000_000L)
    private fun minutesBefore(minutes: Int): Instant = anchorNow - minutes.minutes

    @Test
    fun anchorFallsBackToLookbackWhenNothingPrecedes() {
        val anchor = detourAnchorStart(
            now = anchorNow,
            detours = emptyList(),
            focusSessions = emptyList(),
            windowStart = minutesBefore(600),
        )
        assertEquals(minutesBefore(120), anchor)
    }

    @Test
    fun anchorNeverReachesBeforeTheDayWindow() {
        val anchor = detourAnchorStart(
            now = anchorNow,
            detours = emptyList(),
            focusSessions = emptyList(),
            windowStart = minutesBefore(30),
        )
        assertEquals(minutesBefore(30), anchor)
    }

    @Test
    fun anchorUsesTheLastDetourEnd() {
        val anchor = detourAnchorStart(
            now = anchorNow,
            detours = listOf(
                DetourEpisode(minutesBefore(90), minutesBefore(80), "vieux"),
                DetourEpisode(minutesBefore(50), minutesBefore(40), "récent"),
            ),
            focusSessions = emptyList(),
            windowStart = minutesBefore(600),
        )
        assertEquals(minutesBefore(40), anchor)
    }

    @Test
    fun anchorUsesTheLastFocusEndWhenItIsNewer() {
        val anchor = detourAnchorStart(
            now = anchorNow,
            detours = listOf(DetourEpisode(minutesBefore(90), minutesBefore(80), "détour")),
            focusSessions = listOf(
                FocusSessionRecord(minutesBefore(60), minutesBefore(25), "écrire", null),
            ),
            windowStart = minutesBefore(600),
        )
        assertEquals(minutesBefore(25), anchor)
    }

    @Test
    fun anchorIgnoresBoundariesOlderThanTheLookback() {
        val anchor = detourAnchorStart(
            now = anchorNow,
            detours = listOf(DetourEpisode(minutesBefore(400), minutesBefore(300), "ce matin")),
            focusSessions = emptyList(),
            windowStart = minutesBefore(600),
        )
        assertEquals(minutesBefore(120), anchor)
    }

    @Test
    fun anchorNeverExceedsNow() {
        val anchor = detourAnchorStart(
            now = anchorNow,
            detours = listOf(DetourEpisode(minutesBefore(10), anchorNow + 5.minutes, "en avance")),
            focusSessions = emptyList(),
            windowStart = minutesBefore(600),
        )
        assertEquals(anchorNow, anchor)
    }
```

Add these imports to the top of the file (it currently imports `kotlinx.datetime.TimeZone`,
`kotlin.test.*` and `kotlin.time.Instant`):

```kotlin
import kotlin.time.Duration.Companion.minutes
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.DetoursTest"`
Expected: FAIL — compilation error, `Unresolved reference: detourAnchorStart`.

- [ ] **Step 3: Write the implementation**

Add to `core/src/commonMain/kotlin/fr/dayview/app/Detours.kt`, after
`detourDefaultStartMinutes`:

```kotlin
/** How far back the retroactive default start may reach when no boundary is nearer. */
val DETOUR_ANCHOR_MAX_LOOKBACK: Duration = 120.minutes

/**
 * Default start for a retroactive detour: the last boundary before [now] — the end of the most
 * recent detour or focus session — clamped so it is never older than [maxLookback] nor earlier
 * than [windowStart], and never later than [now].
 *
 * A duration is a guess; a boundary is knowledge. The clamps keep the proposal plausible on a
 * day that started hours ago with nothing declared since.
 */
fun detourAnchorStart(
    now: Instant,
    detours: List<DetourEpisode>,
    focusSessions: List<FocusSessionRecord>,
    windowStart: Instant,
    maxLookback: Duration = DETOUR_ANCHOR_MAX_LOOKBACK,
): Instant {
    val lastDetourEnd = detours.maxOfOrNull { it.end }
    val lastFocusEnd = focusSessions.maxOfOrNull { it.end }
    val floor = maxOf(now - maxLookback, windowStart)
    val boundary = listOfNotNull(lastDetourEnd, lastFocusEnd).maxOrNull()
    return maxOf(boundary ?: floor, floor).coerceAtMost(now)
}
```

`Duration` and `minutes` are already imported in this file.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.DetoursTest"`
Expected: PASS, all `DetoursTest` tests green.

- [ ] **Step 5: Lint and commit**

```bash
./gradlew ktlintCheck
git add core/src/commonMain/kotlin/fr/dayview/app/Detours.kt core/src/commonTest/kotlin/fr/dayview/app/DetoursTest.kt
git commit -m "Anchor the retroactive detour start on the last boundary"
```

---

### Task 2: Motif-free start, motif-at-stop close

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt:539-566`
  (`startOpenDetour`, `stopOpenDetour`)
- Test: `core/src/commonTest/kotlin/fr/dayview/app/OpenDetourTest.kt`

**Interfaces:**
- Consumes: `state.openDetourStart` / `openDetourCategory` / `openDetourDescription`,
  `state.detoursToday`, `commitDetours(episodes, pushCategory)`, `persistState()`,
  `startOfLocalDay(now)`, `sanitizeDetourCategory`, `sanitizeDetourDescription` — all existing.
- Produces:
  - `startOpenDetour(category: String = "", description: String = "")` — now accepts a blank
    motif and starts during an active focus session.
  - `stopOpenDetour(category: String, description: String = "")` — commits the capped span.
  - `cancelOpenDetour()` — clears without committing.
  - `OPEN_DETOUR_MAX_SPAN: Duration` (4 hours), used by the UI plans to flag a capped range.

`startPomodoro` keeps its `if (state.openDetourStart != null) return` guard — the asymmetry is
deliberate and specified. Do not touch it.

**Breaking change:** the old no-argument `stopOpenDetour()` disappears. Its only call sites are
`shared/src/commonMain/kotlin/fr/dayview/app/App.kt:617` and
`DayViewTodayScreen.kt:224/378/574`. Those belong to the Compose UI plan, so this task keeps
them compiling by giving `description` a default and letting the Compose layer pass the
already-running category — see Step 3's note.

- [ ] **Step 1: Rewrite the affected tests**

In `core/src/commonTest/kotlin/fr/dayview/app/OpenDetourTest.kt`, **replace** the four tests
`startIgnoresBlankCategory`, `startRefusedWhileFocusRunning`, `stopRecordsEpisodeAndClears`,
and `stopPersistsClearedStateEvenWithBlankCategory` with:

```kotlin
    @Test
    fun startAcceptsBlankCategory() {
        val c = controller(DayPreferencesSnapshot())
        c.startOpenDetour()
        assertEquals(now, c.state.openDetourStart)
        assertEquals("", c.state.openDetourCategory)
    }

    @Test
    fun startAllowedWhileFocusRunning() {
        val c = controller(DayPreferencesSnapshot(pomodoroEnd = now + 25.minutes))
        c.startOpenDetour()
        assertEquals(now, c.state.openDetourStart)
        assertTrue(c.state.focusIsActive)
    }

    @Test
    fun stopRecordsEpisodeWithTheMotifGivenAtStop() {
        val c = controller(DayPreferencesSnapshot(openDetourStart = now - 15.minutes))
        c.stopOpenDetour("  Réunion\nimprévue ", "avec Paul")
        assertNull(c.state.openDetourStart)
        assertEquals("", c.state.openDetourCategory)
        val episode = c.state.detoursToday.single()
        assertEquals("Réunion imprévue", episode.category)
        assertEquals("avec Paul", episode.description)
        assertEquals(now, episode.end)
        assertEquals(15.minutes, episode.end - episode.start)
    }

    @Test
    fun stopIgnoresABlankMotif() {
        val c = controller(DayPreferencesSnapshot(openDetourStart = now - 15.minutes))
        c.stopOpenDetour("   ")
        assertEquals(now - 15.minutes, c.state.openDetourStart)
        assertTrue(c.state.detoursToday.isEmpty())
    }

    @Test
    fun stopCapsTheSpanAtFourHours() {
        val c = controller(DayPreferencesSnapshot(openDetourStart = now - 9.hours))
        c.stopOpenDetour("Oubli")
        val episode = c.state.detoursToday.single()
        assertEquals(now, episode.end)
        assertEquals(4.hours, episode.end - episode.start)
    }

    @Test
    fun stopNeverCrossesMidnight() {
        // 01:00 local: the 4 h cap would still reach into yesterday, so the day floor wins.
        val startOfToday = startOfLocalDay(now)
        val oneAm = startOfToday + 1.hours
        val c = DayViewController(
            DefaultDayPreferences,
            CoroutineScope(Dispatchers.Unconfined),
            initialSnapshot = DayPreferencesSnapshot(openDetourStart = oneAm - 3.hours),
            initialNow = oneAm,
        )
        c.stopOpenDetour("Nuit blanche")
        val episode = c.state.detoursToday.single()
        assertEquals(startOfToday, episode.start)
        assertEquals(oneAm, episode.end)
    }

    @Test
    fun cancelClearsWithoutRecording() {
        val c = controller(DayPreferencesSnapshot(openDetourStart = now - 15.minutes))
        c.cancelOpenDetour()
        assertNull(c.state.openDetourStart)
        assertEquals("", c.state.openDetourCategory)
        assertTrue(c.state.detoursToday.isEmpty())
    }
```

Also update `startIgnoredWhileOpenDetourAlreadyRunning` — it still passes, but its call
`c.startOpenDetour("Deuxième", "x")` is fine as-is. Leave it.

Add this import to the file:

```kotlin
import kotlin.time.Duration.Companion.hours
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.OpenDetourTest"`
Expected: FAIL — compilation error, `Unresolved reference: cancelOpenDetour`, plus
`No value passed for parameter 'category'` on `stopOpenDetour`.

- [ ] **Step 3: Write the implementation**

In `core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt`, replace `startOpenDetour`
and `stopOpenDetour` (lines 539-566) with:

```kotlin
    /**
     * Enter a detour now. The motif is optional here on purpose: at the moment you are pulled
     * away, a form is the wrong thing to ask for — the motif is collected by [stopOpenDetour],
     * when you know what it was. Allowed during a focus session; the countdown keeps running
     * and the span is hollowed out of engaged time.
     */
    fun startOpenDetour(
        category: String = "",
        description: String = "",
    ) {
        // Only one open detour at a time.
        if (state.openDetourStart != null) return
        state = state.copy(
            openDetourStart = state.now,
            openDetourCategory = sanitizeDetourCategory(category),
            openDetourDescription = sanitizeDetourDescription(description),
        )
        persistState()
    }

    /**
     * Close the open detour into an episode ending now, using the motif supplied at stop time.
     * A blank motif is refused and leaves the detour open — the caller's form is responsible
     * for requiring one. The span is capped at [OPEN_DETOUR_MAX_SPAN] and floored at the start
     * of the local day, so a detour forgotten open overnight can neither cross midnight nor
     * record a monstrous episode.
     */
    fun stopOpenDetour(
        category: String,
        description: String = "",
    ) {
        val start = state.openDetourStart ?: return
        val clean = sanitizeDetourCategory(category)
        if (clean.isEmpty()) return
        val end = state.now
        val flooredStart = maxOf(start, end - OPEN_DETOUR_MAX_SPAN, startOfLocalDay(end))
        state = state.copy(openDetourStart = null, openDetourCategory = "", openDetourDescription = "")
        if (flooredStart >= end) {
            // Degenerate span (clock skew): drop the episode but persist the cleared state.
            persistState()
            return
        }
        commitDetours(
            state.detoursToday + DetourEpisode(flooredStart, end, clean, sanitizeDetourDescription(description)),
            pushCategory = clean,
        )
    }

    /** Discard the open detour without recording anything — for a toggle hit by accident. */
    fun cancelOpenDetour() {
        if (state.openDetourStart == null) return
        state = state.copy(openDetourStart = null, openDetourCategory = "", openDetourDescription = "")
        persistState()
    }
```

Add the cap constant to `core/src/commonMain/kotlin/fr/dayview/app/Detours.kt`, next to
`DETOUR_ANCHOR_MAX_LOOKBACK` from Task 1:

```kotlin
/** Longest span an open detour may record; a longer one was forgotten, not lived. */
val OPEN_DETOUR_MAX_SPAN: Duration = 4.hours
```

and add its import to that file:

```kotlin
import kotlin.time.Duration.Companion.hours
```

Then keep the Compose layer compiling: in
`shared/src/commonMain/kotlin/fr/dayview/app/App.kt:617`, change

```kotlin
stopOpenDetour = { controller.stopOpenDetour() },
```

to

```kotlin
stopOpenDetour = { controller.stopOpenDetour(controller.state.openDetourCategory) },
```

This is a temporary shim: it reproduces the old behaviour (commit with whatever motif the
detour was started with) so `:shared` builds. The Compose UI plan replaces it with the closure
form.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.OpenDetourTest"`
Expected: PASS.

Then confirm nothing downstream broke:

Run: `./gradlew :shared:desktopTest`
Expected: PASS — in particular `OpenDetourPanelTest` and `SyncMapperOpenDetourTest`.

- [ ] **Step 5: Lint and commit**

```bash
./gradlew ktlintCheck
git add core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt core/src/commonMain/kotlin/fr/dayview/app/Detours.kt core/src/commonTest/kotlin/fr/dayview/app/OpenDetourTest.kt shared/src/commonMain/kotlin/fr/dayview/app/App.kt
git commit -m "Start a detour without a motif and collect it at stop"
```

---

### Task 3: Hollow the focus session on the session-derived path

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt:505-522`
  (`appendEngagedSession`)
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt`

**Interfaces:**
- Consumes: `deriveEngagedIntervals(sessionStart, effectiveEnd, detours)` from
  `FocusIntervals.kt`, `state.detoursToday`, `state.openDetourStart`,
  `state.openDetourCategory`, `derivesEngagedFromSessions`.
- Produces: no new public API. Behaviour only: a focus session closing while a detour is open
  is carved by that detour's span.

This is the Android path (`derivesEngagedFromSessions = true`). Task 4 covers macOS/desktop.

- [ ] **Step 1: Write the failing test**

Append to `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt`, inside the test
class:

```kotlin
    @Test
    fun stoppingFocusCarvesAStillOpenDetour() {
        val start = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val c = DayViewController(
            DefaultDayPreferences,
            CoroutineScope(Dispatchers.Unconfined),
            initialSnapshot = DayPreferencesSnapshot(
                pomodoroEnd = start + 30.minutes,
                pomodoroMinutes = 30,
                focusIntention = "écrire",
                // Pulled away 10 minutes in, still off-path when the session is stopped.
                openDetourStart = start + 10.minutes,
            ),
            initialNow = start + 20.minutes,
            derivesEngagedFromSessions = true,
        )
        c.stopPomodoro()
        // Engaged = [start, start+10]; the open detour carves [start+10, start+20].
        assertEquals(
            listOf(FocusPresenceInterval(start, start + 10.minutes)),
            c.state.focusSessionIntervals,
        )
    }
```

Check the file's existing imports and add whichever of these are missing:

```kotlin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.DayViewSessionTest.stoppingFocusCarvesAStillOpenDetour"`
Expected: FAIL — the assertion reports one interval `[start, start+20min]`, because the open
detour is not committed yet and so was not carved.

- [ ] **Step 3: Write the implementation**

In `core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt`, add this private helper
just above `appendEngagedSession`:

```kotlin
    /**
     * Today's episodes plus, when a detour is still running, a provisional episode covering it
     * so far. A focus session closing mid-detour must be carved by a span that is not committed
     * yet; `deriveEngagedIntervals` clips cuts to the session window, so the later real commit
     * cannot double-count.
     */
    private fun detoursForCarving(): List<DetourEpisode> {
        val openStart = state.openDetourStart ?: return state.detoursToday
        if (state.now <= openStart) return state.detoursToday
        return state.detoursToday + DetourEpisode(openStart, state.now, PROVISIONAL_DETOUR_CATEGORY)
    }
```

`deriveEngagedIntervals` only reads `start`/`end`, but `DetourEpisode` rejects an empty
category elsewhere in the codebase, so give the provisional one a stable non-empty label. Add
it to `core/src/commonMain/kotlin/fr/dayview/app/Detours.kt`:

```kotlin
/** Category carried by the provisional episode built from a still-open detour. Never stored. */
const val PROVISIONAL_DETOUR_CATEGORY = "?"
```

Then in `appendEngagedSession`, change

```kotlin
        val derived = deriveEngagedIntervals(start, effectiveEnd, state.detoursToday)
```

to

```kotlin
        val derived = deriveEngagedIntervals(start, effectiveEnd, detoursForCarving())
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.DayViewSessionTest"`
Expected: PASS, whole class green.

- [ ] **Step 5: Lint and commit**

```bash
./gradlew ktlintCheck
git add core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt core/src/commonMain/kotlin/fr/dayview/app/Detours.kt core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt
git commit -m "Carve a still-open detour out of the closing focus session"
```

---

### Task 4: Hollow the focus session on the per-tick presence path

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/PresenceCoordinator.kt:43-69` (`observe`)
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt:97-104` (the `observe`
  call in `tick`)
- Test: `core/src/commonTest/kotlin/fr/dayview/app/` — create
  `PresenceCoordinatorDetourTest.kt`

**Interfaces:**
- Consumes: `classifyFrontmost(frontmostBundleId, onGoalBundleIds, dayViewBundleId)`,
  `OnGoalState`, `PresenceCoordinator.Result`.
- Produces: `PresenceCoordinator.observe(..., detourOpen: Boolean = false)` — a new **trailing
  parameter with a default**, so existing call sites keep compiling.

This is the load-bearing half on macOS. `presence.observe` has no knowledge of detours today,
so a rabbit-hole inside an on-goal app still counts as engaged. A declared detour must outrank
app inference.

While a detour is open the tick is also **exempt from the drift nudge**: you already know you
are off path, so a nudge is noise.

- [ ] **Step 1: Write the failing test**

Create `core/src/commonTest/kotlin/fr/dayview/app/PresenceCoordinatorDetourTest.kt`:

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Ticks are one second apart on purpose. `FocusResumeDetector` treats any gap of 15 s or more
 * as a resumption, and a resumption suppresses the drift nudge for that tick — minute-spaced
 * ticks would make the nudge assertions pass for entirely the wrong reason.
 */
class PresenceCoordinatorDetourTest {
    private val start = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val onGoalApp = "com.example.editor"
    private val offGoalApp = "com.example.social"
    private val onGoal = setOf(onGoalApp)

    private fun PresenceCoordinator.tickAt(
        second: Int,
        detourOpen: Boolean,
        bundleId: String = onGoalApp,
    ): PresenceCoordinator.Result = observe(
        now = start + second.seconds,
        isFocusActive = true,
        frontmostBundleId = bundleId,
        onGoalBundleIds = onGoal,
        pomodoroEnd = start + 60.minutes,
        dayKey = dayKeyOf(start),
        detourOpen = detourOpen,
    )

    private fun PresenceCoordinator.Result.engagedMinutes(): Long =
        sessionIntervals.sumOf { (it.end - it.start).inWholeMinutes }

    @Test
    fun anOpenDetourStopsEngagedTimeEvenOnAnOnGoalApp() {
        val coordinator = PresenceCoordinator()
        // Five minutes of genuine engagement on an on-goal app.
        for (second in 0..300) coordinator.tickAt(second, detourOpen = false)
        // Detour declared, frontmost app unchanged: rabbit-holing inside the editor.
        var settled = 0L
        for (second in 301..400) settled = coordinator.tickAt(second, detourOpen = true).engagedMinutes()
        var later = 0L
        for (second in 401..900) later = coordinator.tickAt(second, detourOpen = true).engagedMinutes()

        assertTrue(settled > 0, "engaged time must have accumulated before the detour")
        assertEquals(settled, later, "a declared detour must outrank the on-goal app classification")
    }

    @Test
    fun theDriftNudgeFiresOnASustainedOffGoalApp() {
        // Control: without this, the suppression test below cannot tell "suppressed" from
        // "would never have fired anyway".
        val coordinator = PresenceCoordinator()
        val fired = (0..300).any {
            coordinator.tickAt(it, detourOpen = false, bundleId = offGoalApp).driftReminderAt != null
        }
        assertTrue(fired, "sustained off-goal must nudge when no detour is declared")
    }

    @Test
    fun anOpenDetourSuppressesTheDriftNudge() {
        val coordinator = PresenceCoordinator()
        val fired = (0..300).any {
            coordinator.tickAt(it, detourOpen = true, bundleId = offGoalApp).driftReminderAt != null
        }
        assertFalse(fired, "no nudge while the detour is declared — you already know")
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.PresenceCoordinatorDetourTest"`
Expected: FAIL — compilation error, `Cannot find a parameter with this name: detourOpen`.

- [ ] **Step 3: Write the implementation**

In `core/src/commonMain/kotlin/fr/dayview/app/PresenceCoordinator.kt`, change `observe`'s
signature and its first two statements:

```kotlin
    fun observe(
        now: Instant,
        isFocusActive: Boolean,
        frontmostBundleId: String?,
        onGoalBundleIds: Set<String>,
        pomodoroEnd: Instant?,
        dayKey: Long,
        detourOpen: Boolean = false,
    ): Result {
        // A declared detour outranks app inference: rabbit-holing inside an on-goal app is
        // still off the path, and only the user can say so.
        val state = if (detourOpen) OnGoalState.OFF_GOAL else classifyFrontmost(frontmostBundleId, onGoalBundleIds, dayViewBundleId)
        // JVM Main.kt ordering: the resume ritual wins the tick and suppresses a drift nudge.
        val resumeAt = if (resumeDetector.observe(isFocusActive, now)) now else null
        // No nudge while the detour is declared — you already know you are off path.
        val driftFired = !detourOpen &&
            resumeAt == null &&
            driftDetector.observe(isFocusActive, now, frontmostBundleId, onGoalBundleIds)
```

Leave the rest of the method unchanged.

Then in `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt`, add the argument to the
`presence.observe(...)` call in `tick`, after `dayKey`:

```kotlin
            dayKey = dayKeyOf(state.now),
            detourOpen = state.openDetourRunning,
        )
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.PresenceCoordinatorDetourTest"`
Expected: PASS.

- [ ] **Step 5: Run the full gate**

Run:

```bash
./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, no stderr. If `FocusTickDiagnostics` or a desktop presence test
fails, it is asserting the old nudge behaviour — read it before changing it, and only relax an
assertion that genuinely contradicts the "no nudge during a declared detour" rule.

- [ ] **Step 6: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/PresenceCoordinator.kt core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt core/src/commonTest/kotlin/fr/dayview/app/PresenceCoordinatorDetourTest.kt
git commit -m "Let a declared detour outrank the on-goal app classification"
```

---

## What this plan deliberately leaves undone

The two follow-up plans depend on this one:

- **macOS plan:** the mini-window and menu-bar toggles, the `+ Detour` mini menu on the ring,
  the consolidated `DetourEditSheet`, the provisional ring arc, the bridge additions on
  `TodaySnapshot`.
- **Compose plan:** the mini menu in `TodayQuickActions`, the closure form replacing the
  `App.kt` shim from Task 2, `DetourCaptureContent` and `DetourEditForm` collapsing into one
  form, retiring `detourDefaultStartMinutes` in favour of `detourAnchorStart`, and the new
  strings in both `values/strings.xml` and `values-fr/strings.xml`.

After Task 4 the app behaves exactly as before from the outside, except that a detour can now
be started during a focus session and correctly hollows engaged time on both platforms.
