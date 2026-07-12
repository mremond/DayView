# Off-window Detours Honesty Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make off-window detour time honest — accounted for in the total and marked in the list — without changing the work-window ring, and stop the morning clamp from shortening long quick-captures.

**Architecture:** One pure predicate (`detourMidpointOutsideWindow`) drives the ring drop (unchanged), a new off-window duration total, and a list row tag, so the three surfaces can never disagree. The `addDetour` quick-capture keeps its full span, floored only at the start of the local day.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, kotlinx-datetime, kotlin.time (`Instant`/`Duration`), Compose resources for i18n, kotlin.test.

## Global Constraints

- ktlint is enforced; run `./gradlew ktlintCheck` (or `ktlintFormat`) before committing.
- Full gate before any commit: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest` — must pass with no errors or stderr.
- Compose resources string formatting: single `%` placeholders (`%1$s`), never `%%`.
- UI tests must NOT assert `stringResource` text (unresolved under `runComposeUiTest` on CI) — cover logic in pure functions and controller state.
- Domain time is `kotlin.time.Instant`/`Duration`; epoch millis only at serialization boundaries.
- No reference to Claude/Anthropic/AI in commit messages.

---

### Task 1: Pure off-window logic in Detours.kt

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/Detours.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DetoursTest.kt`

**Interfaces:**
- Consumes: `DetourEpisode` (existing), `dayKeyOf`-style kotlinx.datetime usage already imported in the file.
- Produces:
  - `fun detourMidpointOutsideWindow(episode: DetourEpisode, windowStart: Instant, windowEnd: Instant): Boolean`
  - `fun offWindowDetoursTotal(windowStart: Instant, windowEnd: Instant, episodes: List<DetourEpisode>): Duration`
  - `fun startOfLocalDay(now: Instant, timeZone: TimeZone = TimeZone.currentSystemDefault()): Instant`

- [ ] **Step 1: Write the failing tests**

Add to `composeApp/src/commonTest/kotlin/fr/dayview/app/DetoursTest.kt` (the file already defines `private fun t(ms: Long): Instant` and imports `kotlinx.datetime.TimeZone`, `kotlin.test.*`, `kotlin.time.Instant`):

```kotlin
    @Test
    fun midpointOutsideWindowMatchesBodyDrop() {
        val start = t(10_000_000L)
        val end = t(20_000_000L)
        val before = DetourEpisode(t(0L), t(1_000_000L), "early") // midpoint 500_000 < start
        val inside = DetourEpisode(t(12_000_000L), t(14_000_000L), "mid") // midpoint 13_000_000 in window
        assertTrue(detourMidpointOutsideWindow(before, start, end))
        assertTrue(!detourMidpointOutsideWindow(inside, start, end))
        // An episode dropped from the ring is exactly one flagged off-window.
        assertEquals(emptyList(), detourBodies(start, end, listOf(before)))
        assertEquals(1, detourBodies(start, end, listOf(inside)).size)
    }

    @Test
    fun offWindowTotalSumsOnlyDroppedEpisodes() {
        val start = t(10_000_000L)
        val end = t(20_000_000L)
        val before = DetourEpisode(t(0L), t(2_000_000L), "early") // 2000s duration, midpoint 1_000_000 outside
        val inside = DetourEpisode(t(12_000_000L), t(15_000_000L), "mid") // 3000s, midpoint 13_500_000 inside
        val total = offWindowDetoursTotal(start, end, listOf(before, inside))
        assertEquals(before.duration, total)
        assertEquals(kotlin.time.Duration.ZERO, offWindowDetoursTotal(start, end, emptyList()))
        assertEquals(kotlin.time.Duration.ZERO, offWindowDetoursTotal(start, end, listOf(inside)))
    }

    @Test
    fun startOfLocalDayIsMidnightOfTheInstantsDay() {
        val zone = TimeZone.UTC
        val noon = Instant.parse("2026-07-12T12:34:56Z")
        assertEquals(Instant.parse("2026-07-12T00:00:00Z"), startOfLocalDay(noon, zone))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DetoursTest"`
Expected: FAIL — unresolved references `detourMidpointOutsideWindow`, `offWindowDetoursTotal`, `startOfLocalDay`.

- [ ] **Step 3: Add the pure functions and refactor `detourBodies`**

In `composeApp/src/commonMain/kotlin/fr/dayview/app/Detours.kt`, add `import kotlinx.datetime.LocalDateTime` is already present; ensure `TimeZone`, `toInstant`, `toLocalDateTime` imports exist (they do). Add these functions near `detourBodies`:

```kotlin
/** Start of the local calendar day containing [now]; mirrors the day-window construction. */
fun startOfLocalDay(
    now: Instant,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Instant {
    val local = now.toLocalDateTime(timeZone)
    return LocalDateTime(year = local.year, month = local.month, day = local.day, hour = 0, minute = 0)
        .toInstant(timeZone)
}

/**
 * True when the episode's midpoint falls outside the day window — the exact condition under
 * which [detourBodies] drops the episode from the ring. Shared so the ring, the off-window
 * total and the list tag can never disagree.
 */
fun detourMidpointOutsideWindow(
    episode: DetourEpisode,
    windowStart: Instant,
    windowEnd: Instant,
): Boolean {
    val midpoint = episode.start + episode.duration / 2
    return midpoint < windowStart || midpoint > windowEnd
}

/** Summed duration of the episodes the ring drops (midpoint outside the window). */
fun offWindowDetoursTotal(
    windowStart: Instant,
    windowEnd: Instant,
    episodes: List<DetourEpisode>,
): Duration = episodes.fold(Duration.ZERO) { acc, episode ->
    if (detourMidpointOutsideWindow(episode, windowStart, windowEnd)) acc + episode.duration else acc
}
```

Then refactor the inline check in `detourBodies` to reuse the predicate. Replace:

```kotlin
        val midpoint = episode.start + episode.duration / 2
        if (midpoint < windowStart || midpoint > windowEnd) return@mapNotNull null
        val fraction = ((midpoint - windowStart) / total).toFloat()
```

with:

```kotlin
        val midpoint = episode.start + episode.duration / 2
        if (detourMidpointOutsideWindow(episode, windowStart, windowEnd)) return@mapNotNull null
        val fraction = ((midpoint - windowStart) / total).toFloat()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DetoursTest"`
Expected: PASS (all DetoursTest tests, including the pre-existing `bodiesOutsideTheWindowAreDropped`).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/Detours.kt composeApp/src/commonTest/kotlin/fr/dayview/app/DetoursTest.kt
git commit -m "Add shared off-window detour predicate and total"
```

---

### Task 2: Fix the morning clamp and expose the off-window total

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt` (`addDetour` ~line 326; state accessors ~line 118)
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt`

**Interfaces:**
- Consumes: `startOfLocalDay`, `offWindowDetoursTotal` (Task 1); existing `dayWindow`, `detoursToday`, `state.now`, `state.dayWindow`.
- Produces: `DayViewUiState.detoursOffWindowTotalToday: Duration` accessor for the today screen (Task 3).

- [ ] **Step 1: Write the failing tests**

Add to `composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt` (already imports `LocalDateTime`, `TimeZone`, `toInstant`, `kotlin.test.*`, and defines `testController` with default preferences whose window is 08:00–18:00 local):

```kotlin
    @Test
    fun addDetourKeepsFullSpanWhenStartPredatesWindow() {
        val zone = TimeZone.currentSystemDefault()
        // 08:30 local, just after the 08:00 window opens.
        val now = LocalDateTime(2026, 7, 12, 8, 30).toInstant(zone).toEpochMilliseconds()
        val preferences = InMemoryDayPreferences()
        val controller = testController(preferences, now)

        controller.addDetour("longue lecture", 60) // would start 07:30, before the window

        val episode = controller.state.detoursToday.single()
        assertEquals(60, episode.duration.inWholeMinutes) // no longer clamped to 30
        assertEquals(t(now), episode.end) // still ends now; the full 60 min is preserved before it
    }

    @Test
    fun addDetourFloorsPathologicalStartAtLocalMidnight() {
        val zone = TimeZone.currentSystemDefault()
        val now = LocalDateTime(2026, 7, 12, 8, 30).toInstant(zone).toEpochMilliseconds()
        val preferences = InMemoryDayPreferences()
        val controller = testController(preferences, now)

        controller.addDetour("marathon", 12 * 60) // 12 h would cross into the previous day

        val episode = controller.state.detoursToday.single()
        assertEquals(startOfLocalDay(t(now)), episode.start) // floored to today's 00:00
        assertEquals(t(now), episode.end)
    }

    @Test
    fun offWindowTotalStateCountsDroppedEpisodes() {
        val zone = TimeZone.currentSystemDefault()
        val now = LocalDateTime(2026, 7, 12, 21, 0).toInstant(zone).toEpochMilliseconds() // evening, past 18:00
        val preferences = InMemoryDayPreferences()
        val controller = testController(preferences, now)

        controller.addDetour("série", 45) // 20:15–21:00, entirely after the window

        assertEquals(45, controller.state.detoursOffWindowTotalToday.inWholeMinutes)
        assertEquals(controller.state.detoursTotalToday, controller.state.detoursOffWindowTotalToday)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: FAIL — `detoursOffWindowTotalToday` unresolved, and the clamp test fails because the current code shortens to 30 min.

- [ ] **Step 3: Fix `addDetour` and add the state accessor**

In `DayViewController.kt`, replace the body of `addDetour`:

```kotlin
    /** Quick capture: the episode ends now and starts [durationMinutes] earlier. */
    fun addDetour(motif: String, durationMinutes: Int) {
        val clean = sanitizeDetourMotif(motif)
        if (clean.isEmpty()) return
        val end = state.now
        // Keep the full declared span; only floor at the start of the local day so a very long
        // capture cannot cross into yesterday and break the day-scoped, time-only list display.
        val start = maxOf(end - durationMinutes.coerceIn(1, 12 * 60).minutes, startOfLocalDay(end))
        commitDetours(state.detoursToday + DetourEpisode(start, end, clean), pushMotif = clean)
    }
```

Add the state accessor next to `detoursTotalToday` (~line 118):

```kotlin
    val detoursOffWindowTotalToday: Duration
        get() {
            val (start, end) = dayWindow
            return offWindowDetoursTotal(start, end, detoursToday)
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: PASS. Verify the pre-existing `addDetourStoresAnEpisodeEndingNowAndPersistsIt` still passes (its 30-min capture at a mid-day instant is unaffected).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt
git commit -m "Keep full detour span on quick capture and expose off-window total"
```

---

### Task 3: Off-window duration hint on the today total line

**Files:**
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Modify: `composeApp/src/commonMain/composeResources/values-fr/strings.xml`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (`CountdownCircle` signature ~line 688, total render ~line 992, two call sites ~line 256 and ~line 312)

**Interfaces:**
- Consumes: `DayViewUiState.detoursOffWindowTotalToday` (Task 2); `formatDurationHm` (existing).
- Produces: no new public API; wires state into the existing total line.

- [ ] **Step 1: Add the string resource (en, then fr)**

In `values/strings.xml`, after the `detours_today` line (line 210):

```xml
    <string name="detours_today_off_window">Detours %1$s (%2$s off window)</string>
```

In `values-fr/strings.xml`, after its `detours_today` line (line 209):

```xml
    <string name="detours_today_off_window">Détours %1$s (%2$s hors plage)</string>
```

- [ ] **Step 2: Add the parameter and render logic**

In `DayViewTodayScreen.kt`, add the import near the other generated-resource imports (next to `import fr.dayview.app.generated.resources.detours_today`):

```kotlin
import fr.dayview.app.generated.resources.detours_today_off_window
```

Add a parameter to `CountdownCircle` right after `detoursTotal` (~line 698):

```kotlin
    detoursTotal: Duration = Duration.ZERO,
    detoursOffWindow: Duration = Duration.ZERO,
```

Replace the total-line block (~line 992-995) so it picks the off-window string when there is off-window time:

```kotlin
                            if (detoursTotal > Duration.ZERO) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    if (detoursOffWindow > Duration.ZERO) {
                                        stringResource(
                                            Res.string.detours_today_off_window,
                                            formatDurationHm(detoursTotal),
                                            formatDurationHm(detoursOffWindow),
                                        )
                                    } else {
                                        stringResource(Res.string.detours_today, formatDurationHm(detoursTotal))
                                    },
                                    color = colors.amber,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = .5.sp,
```

(Leave the closing `)` and the rest of the `Text(...)` call as they are.)

- [ ] **Step 3: Pass the state at both call sites**

At the wide-layout call site (~line 256) and the compact call site (~line 312), add the argument right after `detoursTotal = state.detoursTotalToday,`:

```kotlin
                            detoursTotal = state.detoursTotalToday,
                            detoursOffWindow = state.detoursOffWindowTotalToday,
```

and (compact, note its indentation matches the surrounding block):

```kotlin
                    detoursTotal = state.detoursTotalToday,
                    detoursOffWindow = state.detoursOffWindowTotalToday,
```

- [ ] **Step 4: Verify it compiles and the suites pass**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: PASS, no ktlint errors, no stderr. (Rendering has no unit test per repo convention — logic is covered in Tasks 1–2. The off-window classification the render depends on is tested there.)

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/composeResources/values/strings.xml composeApp/src/commonMain/composeResources/values-fr/strings.xml composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt
git commit -m "Show off-window detour time alongside the day's detour total"
```

---

### Task 4: Tag off-window rows in the detours list

**Files:**
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Modify: `composeApp/src/commonMain/composeResources/values-fr/strings.xml`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DetoursUi.kt` (`DetourListDialog` ~line 392, row render ~line 440)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (`DetourListDialog` call ~line 352)

**Interfaces:**
- Consumes: `detourMidpointOutsideWindow` (Task 1); `state.dayWindow` (existing).
- Produces: `DetourListDialog` gains `windowStart: Instant` and `windowEnd: Instant` parameters.

- [ ] **Step 1: Add the string resource (en, then fr)**

In `values/strings.xml`, after `detour_time_range` (line 213):

```xml
    <string name="detour_off_window_tag">off window</string>
```

In `values-fr/strings.xml`, after its `detour_time_range` (line 212):

```xml
    <string name="detour_off_window_tag">hors plage</string>
```

- [ ] **Step 2: Add window parameters and the row tag**

In `DetoursUi.kt`, add the generated-resource import near the other detour imports:

```kotlin
import fr.dayview.app.generated.resources.detour_off_window_tag
```

Add parameters to `DetourListDialog` (after `now: Instant,` ~line 394):

```kotlin
internal fun DetourListDialog(
    episodes: List<DetourEpisode>,
    now: Instant,
    windowStart: Instant,
    windowEnd: Instant,
    onUpdate: (Int, DetourEpisode) -> Unit,
```

In the row, after the time-range `Text(...)` block (the one built from `Res.string.detour_time_range`, ~line 440-449), add a conditional tag inside the same `Column(Modifier.weight(1f))`:

```kotlin
                                        if (detourMidpointOutsideWindow(episode, windowStart, windowEnd)) {
                                            Text(
                                                stringResource(Res.string.detour_off_window_tag),
                                                color = colors.muted,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium,
                                                letterSpacing = .5.sp,
                                            )
                                        }
```

- [ ] **Step 3: Pass the window from the call site**

In `DayViewTodayScreen.kt`, update the `DetourListDialog(...)` call (~line 352):

```kotlin
            DetourListDialog(
                episodes = state.detoursToday,
                now = state.now,
                windowStart = state.dayWindow.first,
                windowEnd = state.dayWindow.second,
                onUpdate = actions.updateDetour,
                onRemove = actions.removeDetour,
                onAdd = actions.addDetourEpisode,
                onDismiss = { showDetourList = false },
            )
```

- [ ] **Step 4: Verify it compiles and the suites pass**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: PASS, no ktlint errors, no stderr. (Row tagging uses the Task-1 predicate, already unit-tested; no `stringResource` assertion is added.)

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/composeResources/values/strings.xml composeApp/src/commonMain/composeResources/values-fr/strings.xml composeApp/src/commonMain/kotlin/fr/dayview/app/DetoursUi.kt composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt
git commit -m "Tag off-window episodes in the today's detours list"
```

---

## Self-Review

**Spec coverage:**
- Spec A (shared predicate + `offWindowDetoursTotal`, refactor `detourBodies`) → Task 1. ✓
- Spec B (remove window clamp, midnight floor) → Task 2. ✓
- Spec C (off-window duration hint, new string, plumb into `CountdownCircle`) → Task 3. ✓
- Spec D (tag off-window rows, plumb window into `DetourListDialog`) → Task 4. ✓
- Spec E (tests: `offWindowDetoursTotal` cases, drop⇔off-window invariant, clamp fix, floor, no stringResource assertions) → Tasks 1–2 tests; UI tasks rely on the pure-function coverage per repo convention. ✓
- String table (`detours_today_off_window`, `detour_off_window_tag`, en+fr) → Tasks 3 & 4. ✓

**Placeholder scan:** No TBD/TODO; every code step shows the actual code and exact gradle commands. One noted cleanup in Task 2 Step 1 (drop the awkward intermediate assertion if ktlint objects) is explicit, not a placeholder.

**Type consistency:** `detourMidpointOutsideWindow(episode, windowStart, windowEnd)`, `offWindowDetoursTotal(windowStart, windowEnd, episodes)`, `startOfLocalDay(now, timeZone)`, and `detoursOffWindowTotalToday: Duration` are used identically across tasks. `CountdownCircle` param `detoursOffWindow` and `DetourListDialog` params `windowStart`/`windowEnd` match their call sites.
