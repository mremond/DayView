# Calendar Busy Blocks on the Ring — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render calendar busy time as its own per-calendar-colored layer — a thin span-arc plus a small "celestial block" at the event midpoint — so the main ring can go back to meaning past/future alone.

**Architecture:** Add `calendarId` to `BusyInterval` (both platform sources already read it). New pure projections in `CalendarNetTime.kt` mirror the detour system: `busyCalendars` assigns a stable color index per calendar, `busyBlockArcs`/`busyBlockBodies` project intervals onto the ring. `CountdownCircle` drops its grey busy pass and draws the new cool-toned span-arcs + rounded-square blocks after the green sweep. Net-time math keeps a union merge; rendering uses a within-calendar merge.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform (desktop + Android), `kotlin.time.Instant`/`Duration`, Canvas `DrawScope`, JUnit (`commonTest`) / desktop test harness.

## Global Constraints

- JDK 21 toolchain; Android SDK present; `local.properties` must exist in the worktree (already copied).
- ktlint is enforced. Before **every** commit run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest` and confirm no errors or stderr.
- Commit messages in English; describe the change only. **Never** reference Claude/Anthropic/an AI assistant, and **never** add a test-plan or verification section to commit messages.
- Do not reference internal working documents (this plan, the spec) in commit messages.
- Compose UI is not pixel-tested: never assert `stringResource` text in `runComposeUiTest`. Pure functions carry the tests; UI is verified by a throwaway screenshot harness (Task 8).
- Angle convention across the ring: `-90° = window start`, clockwise, `angle = -90f + fraction * 360f`. Reuse it verbatim.
- Work on the current branch `claude/busy-slots-visibility-2da85b`.

---

## File Structure

- `composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt` — data models + all pure projections (`BusyInterval`, merges, `busyCalendars`, `busyBlockArcs`, `busyBlockBodies`). Owns busy/net-time domain logic.
- `composeApp/src/desktopMain/.../CalendarSource.desktop.kt`, `composeApp/src/androidMain/.../CalendarSource.android.kt` — pass `calendarId` through.
- `composeApp/src/commonMain/.../DayViewTheme.kt` — new `busy` cool-tone palette.
- `composeApp/src/commonMain/.../DayViewController.kt` — new state projections `busyBlockArcsState` / `busyBlockBodiesState`.
- `composeApp/src/commonMain/.../DayViewTodayScreen.kt` — `CountdownCircle` rendering + hover tooltip.
- `composeApp/src/commonTest/.../CalendarNetTimeTest.kt` — unit tests for all new pure functions.

---

## Task 1: Add `calendarId` to `BusyInterval` and plumb it through both sources

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt:10-14`
- Modify: `composeApp/src/desktopMain/kotlin/fr/dayview/app/CalendarSource.desktop.kt:45-51`
- Modify: `composeApp/src/androidMain/kotlin/fr/dayview/app/CalendarSource.android.kt:72-76`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/CalendarNetTimeTest.kt`

**Interfaces:**
- Produces: `BusyInterval(start, end, titles = emptyList(), calendarId = "")` — new trailing field, default `""` keeps all existing call sites compiling.

- [ ] **Step 1: Write the failing test** — add to `CalendarNetTimeTest.kt` (net-time must count a cross-calendar overlap once, and the new field must exist):

```kotlin
    @Test
    fun netTimeCountsCrossCalendarOverlapOnce() {
        val zone = TimeZone.of("Europe/Paris")
        val noon = LocalDateTime(2026, 7, 11, 12, 0).toInstant(zone)
        val (start, end) = dayWindow(noon, 8 * 60, 18 * 60, zone)
        val progress = calculateDayProgress(noon, 8 * 60, 18 * 60, zone)
        // Two calendars, same 14:00-15:00 slot -> counted once, not twice.
        val busy = listOf(
            BusyInterval(noon + 2.hours, noon + 3.hours, listOf("Pro"), calendarId = "work"),
            BusyInterval(noon + 2.hours, noon + 3.hours, listOf("Perso"), calendarId = "home"),
        )
        val net = calculateNetTime(progress, noon, start, end, busy)
        assertEquals(1.hours, net.busyRemaining)
    }
```

- [ ] **Step 2: Run it, verify it fails to compile**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.CalendarNetTimeTest"`
Expected: FAIL — `Cannot find a parameter with this name: calendarId`.

- [ ] **Step 3: Add the field** — `CalendarNetTime.kt`, replace the data class:

```kotlin
data class BusyInterval(
    val start: Instant,
    val end: Instant,
    val titles: List<String> = emptyList(),
    val calendarId: String = "",
)
```

- [ ] **Step 4: Run it, verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.CalendarNetTimeTest"`
Expected: PASS. (The union `mergeBusyIntervals` already collapses the identical slot, so busyRemaining is 1 h.)

- [ ] **Step 5: Plumb the desktop source** — `CalendarSource.desktop.kt`, the `BusyInterval(...)` in `busyIntervals` (around line 46). `calId` is already parsed as `parts[2]`; add it:

```kotlin
        BusyInterval(
            Instant.fromEpochMilliseconds(start),
            Instant.fromEpochMilliseconds(end),
            if (title.isBlank()) emptyList() else listOf(title),
            calendarId = calId,
        )
```

- [ ] **Step 6: Plumb the Android source** — `CalendarSource.android.kt`, the `out += BusyInterval(...)` (around line 72). `calId` is already computed at line 68; add it:

```kotlin
                out += BusyInterval(
                    Instant.fromEpochMilliseconds(c.getLong(0)),
                    Instant.fromEpochMilliseconds(c.getLong(1)),
                    listOfNotNull(c.getString(2)),
                    calendarId = calId,
                )
```

- [ ] **Step 7: Full suite + commit**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL.

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt \
  composeApp/src/desktopMain/kotlin/fr/dayview/app/CalendarSource.desktop.kt \
  composeApp/src/androidMain/kotlin/fr/dayview/app/CalendarSource.android.kt \
  composeApp/src/commonTest/kotlin/fr/dayview/app/CalendarNetTimeTest.kt
git commit -m "Carry calendar identity on busy intervals"
```

---

## Task 2: Within-calendar merge + `busyCalendars` color assignment

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/CalendarNetTimeTest.kt`

**Interfaces:**
- Produces:
  - `fun mergeBusyIntervalsByCalendar(intervals: List<BusyInterval>): List<BusyInterval>` — merges only within the same `calendarId`, preserving `calendarId`.
  - `data class BusyCalendar(val calendarId: String, val colorIndex: Int, val total: Duration)`
  - `fun busyCalendars(intervals: List<BusyInterval>): List<BusyCalendar>` — `colorIndex` by first-seen order (intervals sorted by start), `total` = summed duration per calendar.

- [ ] **Step 1: Write the failing tests** — add to `CalendarNetTimeTest.kt`:

```kotlin
    @Test
    fun mergeByCalendarKeepsCalendarsSeparate() {
        val merged = mergeBusyIntervalsByCalendar(
            listOf(
                BusyInterval(t(100), t(300), listOf("A"), calendarId = "work"),
                BusyInterval(t(200), t(400), listOf("B"), calendarId = "home"), // overlaps in time, other calendar
                BusyInterval(t(300), t(500), listOf("C"), calendarId = "work"), // touches A -> merges
            ),
        )
        // work: 100..500 merged; home: 200..400 separate.
        assertEquals(2, merged.size)
        val work = merged.first { it.calendarId == "work" }
        val home = merged.first { it.calendarId == "home" }
        assertEquals(t(100), work.start)
        assertEquals(t(500), work.end)
        assertEquals(t(200), home.start)
        assertEquals(t(400), home.end)
    }

    @Test
    fun busyCalendarsAssignStableColorIndexByFirstSeen() {
        val cals = busyCalendars(
            listOf(
                BusyInterval(t(300), t(400), calendarId = "home"),
                BusyInterval(t(100), t(200), calendarId = "work"), // earliest start -> index 0
                BusyInterval(t(500), t(700), calendarId = "work"),
            ),
        )
        val work = cals.first { it.calendarId == "work" }
        val home = cals.first { it.calendarId == "home" }
        assertEquals(0, work.colorIndex) // earliest start overall
        assertEquals(1, home.colorIndex)
        assertEquals(300L, work.total.inWholeMilliseconds) // 100 + 200
        assertEquals(100L, home.total.inWholeMilliseconds)
    }
```

- [ ] **Step 2: Run, verify fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.CalendarNetTimeTest"`
Expected: FAIL — `Unresolved reference 'mergeBusyIntervalsByCalendar'` / `'busyCalendars'`.

- [ ] **Step 3: Implement** — in `CalendarNetTime.kt`, after `mergeBusyIntervals` (around line 31):

```kotlin
/**
 * Fusionne les créneaux occupés au sein d'un même calendrier uniquement : deux calendriers
 * qui se chevauchent dans le temps restent distincts (pour le rendu par calendrier), alors
 * que [mergeBusyIntervals] fusionne tout (union, pour le calcul du temps net).
 */
fun mergeBusyIntervalsByCalendar(intervals: List<BusyInterval>): List<BusyInterval> =
    intervals.groupBy { it.calendarId }.values.flatMap { mergeBusyIntervals(it) }

/** Un calendrier occupé : couleur stable (premier vu) et durée cumulée sur la journée. */
data class BusyCalendar(val calendarId: String, val colorIndex: Int, val total: Duration)

/**
 * Index de couleur par calendrier, dans l'ordre de première apparition (créneaux triés par
 * début), pour rester stables sur la journée — même convention que [detourSources].
 */
fun busyCalendars(intervals: List<BusyInterval>): List<BusyCalendar> {
    val colorByCal = LinkedHashMap<String, Int>()
    val totalByCal = LinkedHashMap<String, Duration>()
    for (interval in intervals.filter { it.end > it.start }.sortedBy { it.start }) {
        val key = interval.calendarId
        colorByCal.getOrPut(key) { colorByCal.size }
        totalByCal[key] = (totalByCal[key] ?: Duration.ZERO) + (interval.end - interval.start)
    }
    return colorByCal.keys.map { BusyCalendar(it, colorByCal.getValue(it), totalByCal.getValue(it)) }
}
```

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.CalendarNetTimeTest"`
Expected: PASS.

- [ ] **Step 5: Full suite + commit**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt \
  composeApp/src/commonTest/kotlin/fr/dayview/app/CalendarNetTimeTest.kt
git commit -m "Add per-calendar busy merge and color assignment"
```

---

## Task 3: `busyBlockArcs` and `busyBlockBodies` projections

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/CalendarNetTimeTest.kt`

**Interfaces:**
- Consumes: `busyCalendars`, `mergeBusyIntervalsByCalendar` (Task 2).
- Produces:
  - `data class BusyBlockArc(startAngleDegrees: Float, sweepDegrees: Float, colorIndex: Int, titles: List<String>, calendarName: String)`
  - `data class BusyBlockBody(angleDegrees: Float, sizeFraction: Float, colorIndex: Int, titles: List<String>, calendarName: String, start: Instant, end: Instant)`
  - `fun busyBlockArcs(windowStart: Instant, windowEnd: Instant, intervals: List<BusyInterval>, calendarNames: Map<String, String>): List<BusyBlockArc>`
  - `fun busyBlockBodies(windowStart: Instant, windowEnd: Instant, intervals: List<BusyInterval>, calendarNames: Map<String, String>): List<BusyBlockBody>`

- [ ] **Step 1: Write the failing tests** — add to `CalendarNetTimeTest.kt`:

```kotlin
    @Test
    fun busyBlockArcsProjectWithColorAndName() {
        // Fenêtre 0..1000. work 250..500 -> quart..moitié, couleur 0, nom mappé.
        val arcs = busyBlockArcs(
            t(0), t(1000),
            listOf(BusyInterval(t(250), t(500), listOf("Atelier"), calendarId = "work")),
            mapOf("work" to "Travail"),
        )
        assertEquals(1, arcs.size)
        assertEquals(-90f + 0.25f * 360f, arcs[0].startAngleDegrees)
        assertEquals(0.25f * 360f, arcs[0].sweepDegrees)
        assertEquals(0, arcs[0].colorIndex)
        assertEquals(listOf("Atelier"), arcs[0].titles)
        assertEquals("Travail", arcs[0].calendarName)
    }

    @Test
    fun busyBlockArcsFallBackToBlankNameForUnknownCalendar() {
        val arcs = busyBlockArcs(
            t(0), t(1000),
            listOf(BusyInterval(t(100), t(200), calendarId = "ghost")),
            emptyMap(),
        )
        assertEquals("", arcs[0].calendarName)
    }

    @Test
    fun busyBlockBodiesProjectMidpointAndClampSize() {
        // 5 min .. 60 min band ; ici durée 300 ms sur fenêtre 0..1000 -> minuscule -> sizeFraction 0.
        val bodies = busyBlockBodies(
            t(0), t(1000),
            listOf(BusyInterval(t(400), t(700), listOf("Point"), calendarId = "work")),
            mapOf("work" to "Travail"),
        )
        assertEquals(1, bodies.size)
        assertEquals(-90f + 0.55f * 360f, bodies[0].angleDegrees) // midpoint 550/1000
        assertEquals(0f, bodies[0].sizeFraction) // 300 ms << 5 min -> clamped to 0
        assertEquals("Travail", bodies[0].calendarName)
    }

    @Test
    fun busyBlockBodiesDropMidpointOutsideWindow() {
        // Créneau -400..-200 : hors fenêtre après clip il n'existe pas ; midpoint hors fenêtre.
        val bodies = busyBlockBodies(
            t(0), t(1000),
            listOf(BusyInterval(t(1200), t(1600), calendarId = "work")),
            mapOf("work" to "Travail"),
        )
        assertEquals(emptyList(), bodies)
    }
```

- [ ] **Step 2: Run, verify fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.CalendarNetTimeTest"`
Expected: FAIL — `Unresolved reference 'busyBlockArcs'` / `'busyBlockBodies'`.

- [ ] **Step 3: Implement** — in `CalendarNetTime.kt`. First add the minutes import near the top imports:

```kotlin
import kotlin.time.Duration.Companion.minutes
```

Then add, after `busyCalendars` (from Task 2):

```kotlin
/** Un créneau agenda projeté en arc coloré fin sur l'anneau. */
data class BusyBlockArc(
    val startAngleDegrees: Float,
    val sweepDegrees: Float,
    val colorIndex: Int,
    val titles: List<String>,
    val calendarName: String,
)

/** Un créneau agenda projeté en « bloc céleste » à son milieu, taille selon la durée. */
data class BusyBlockBody(
    val angleDegrees: Float,
    val sizeFraction: Float,
    val colorIndex: Int,
    val titles: List<String>,
    val calendarName: String,
    val start: Instant,
    val end: Instant,
)

private val MIN_BUSY_BODY_DURATION = 5.minutes
private val MAX_BUSY_BODY_DURATION = 60.minutes

/**
 * Arcs colorés par calendrier : fusion intra-calendrier, clip à la fenêtre, couleur stable
 * de [busyCalendars], nom depuis [calendarNames] (vide si inconnu). Même convention d'angle
 * que [busyArcs].
 */
fun busyBlockArcs(
    windowStart: Instant,
    windowEnd: Instant,
    intervals: List<BusyInterval>,
    calendarNames: Map<String, String>,
): List<BusyBlockArc> {
    val total = windowEnd - windowStart
    if (total <= Duration.ZERO) return emptyList()
    val colorByCal = busyCalendars(intervals).associate { it.calendarId to it.colorIndex }
    val clipped = mergeBusyIntervalsByCalendar(
        intervals.map {
            it.copy(
                start = it.start.coerceIn(windowStart, windowEnd),
                end = it.end.coerceIn(windowStart, windowEnd),
            )
        },
    )
    return clipped.mapNotNull {
        if (it.end <= it.start) return@mapNotNull null
        val fStart = ((it.start - windowStart) / total).toFloat()
        val fEnd = ((it.end - windowStart) / total).toFloat()
        BusyBlockArc(
            startAngleDegrees = -90f + fStart * 360f,
            sweepDegrees = (fEnd - fStart) * 360f,
            colorIndex = colorByCal[it.calendarId] ?: 0,
            titles = it.titles,
            calendarName = calendarNames[it.calendarId] ?: "",
        )
    }
}

/**
 * Blocs célestes : un par créneau fusionné intra-calendrier, à son milieu ; les créneaux dont
 * le milieu tombe hors fenêtre sont écartés. `sizeFraction` 0..1 depuis la durée bornée à
 * [5 min, 60 min] — même règle que [detourBodies].
 */
fun busyBlockBodies(
    windowStart: Instant,
    windowEnd: Instant,
    intervals: List<BusyInterval>,
    calendarNames: Map<String, String>,
): List<BusyBlockBody> {
    val total = windowEnd - windowStart
    if (total <= Duration.ZERO) return emptyList()
    val colorByCal = busyCalendars(intervals).associate { it.calendarId to it.colorIndex }
    return mergeBusyIntervalsByCalendar(intervals).sortedBy { it.start }.mapNotNull { interval ->
        if (interval.end <= interval.start) return@mapNotNull null
        val duration = interval.end - interval.start
        val midpoint = interval.start + duration / 2
        if (midpoint < windowStart || midpoint > windowEnd) return@mapNotNull null
        val fraction = ((midpoint - windowStart) / total).toFloat()
        val sizeFraction = ((duration - MIN_BUSY_BODY_DURATION) / (MAX_BUSY_BODY_DURATION - MIN_BUSY_BODY_DURATION))
            .toFloat().coerceIn(0f, 1f)
        BusyBlockBody(
            angleDegrees = -90f + fraction * 360f,
            sizeFraction = sizeFraction,
            colorIndex = colorByCal[interval.calendarId] ?: 0,
            titles = interval.titles,
            calendarName = calendarNames[interval.calendarId] ?: "",
            start = interval.start,
            end = interval.end,
        )
    }
}
```

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.CalendarNetTimeTest"`
Expected: PASS.

- [ ] **Step 5: Full suite + commit**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt \
  composeApp/src/commonTest/kotlin/fr/dayview/app/CalendarNetTimeTest.kt
git commit -m "Project busy blocks to ring arcs and bodies"
```

---

## Task 4: Cool-tone `busy` palette

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTheme.kt`

**Interfaces:**
- Produces: `DayViewColors.busy: List<Color>` — a 6-entry cool-tone palette, dark and light variants.

No unit test (theme data only); verified by compile + the full suite.

- [ ] **Step 1: Add the field** — `DayViewTheme.kt`, in the `DayViewColors` data class, after `detours`:

```kotlin
    val detours: List<Color>,
    val busy: List<Color>,
```

- [ ] **Step 2: Dark palette** — in `DarkDayViewColors`, after the `detours = listOf(...)` block, add:

```kotlin
    busy = listOf(
        Color(0xFF6EC6FF), // sky
        Color(0xFF6FD8C9), // teal
        Color(0xFF8AA6FF), // periwinkle
        Color(0xFFB39DFF), // violet
        Color(0xFF7FB4CC), // slate cyan
        Color(0xFF9FC0E8), // steel
    ),
```

- [ ] **Step 3: Light palette** — in `LightDayViewColors`, after its `detours = listOf(...)` block, add:

```kotlin
    busy = listOf(
        Color(0xFF2C6FA6), // sky
        Color(0xFF2E8B84), // teal
        Color(0xFF3F52A8), // periwinkle
        Color(0xFF6A4FA8), // violet
        Color(0xFF34738A), // slate cyan
        Color(0xFF4E6E96), // steel
    ),
```

- [ ] **Step 4: Full suite + commit**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL (both palettes now supply every field).

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTheme.kt
git commit -m "Add cool-tone palette for calendar blocks"
```

---

## Task 5: Controller state projections

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt:72-78`

**Interfaces:**
- Consumes: `busyBlockArcs`, `busyBlockBodies` (Task 3); existing `availableCalendars: List<CalendarInfo>`, `busyIntervals`, `dayWindow`, `netTimeSettings`.
- Produces: `DayViewUiState.busyBlockArcsState: List<BusyBlockArc>`, `DayViewUiState.busyBlockBodiesState: List<BusyBlockBody>`.

No unit test (thin state glue over tested pure functions, matching `busyArcsState`); verified by compile + suite.

- [ ] **Step 1: Add the projections** — `DayViewController.kt`, immediately after the existing `busyArcsState` getter (ends around line 78):

```kotlin
    private val calendarNamesById: Map<String, String>
        get() = availableCalendars.associate { it.id to it.displayName }

    val busyBlockArcsState: List<BusyBlockArc>
        get() = if (netTimeSettings.enabled) {
            val (start, end) = dayWindow
            busyBlockArcs(start, end, busyIntervals, calendarNamesById)
        } else {
            emptyList()
        }

    val busyBlockBodiesState: List<BusyBlockBody>
        get() = if (netTimeSettings.enabled) {
            val (start, end) = dayWindow
            busyBlockBodies(start, end, busyIntervals, calendarNamesById)
        } else {
            emptyList()
        }
```

- [ ] **Step 2: Full suite + commit**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt
git commit -m "Expose calendar block projections in today state"
```

---

## Task 6: Render span-arcs and blocks in `CountdownCircle`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (params ~675-689; grey busy pass ~775-785; after green sweep ~846; call sites ~240 and ~294)

**Interfaces:**
- Consumes: `state.busyBlockArcsState`, `state.busyBlockBodiesState` (Task 5); `colors.busy` (Task 4).
- Produces: `CountdownCircle` gains params `busyBlockArcs: List<BusyBlockArc> = emptyList()`, `busyBlockBodies: List<BusyBlockBody> = emptyList()`. The old `busyArcs` param stays (hover still uses it until Task 7).

- [ ] **Step 1: Add the imports** — `DayViewTodayScreen.kt`, with the other `androidx.compose.ui.geometry` imports:

```kotlin
import androidx.compose.ui.geometry.CornerRadius
```

(`Size` and `Offset` are already imported.)

- [ ] **Step 2: Add the params** — in `CountdownCircle`'s signature, after `detourBodies` / `detoursTotal`:

```kotlin
    detourBodies: List<DetourBody> = emptyList(),
    detoursTotal: Duration = Duration.ZERO,
    busyBlockArcs: List<BusyBlockArc> = emptyList(),
    busyBlockBodies: List<BusyBlockBody> = emptyList(),
    hasGoal: Boolean = false,
```

- [ ] **Step 3: Remove the grey busy pass** — delete this whole block (the `busyArcs.forEach { ... colors.overlay.copy(alpha = .35f) ... }`):

```kotlin
                    busyArcs.forEach { arc ->
                        drawArc(
                            color = colors.overlay.copy(alpha = .35f),
                            startAngle = arc.startAngleDegrees,
                            sweepAngle = arc.sweepDegrees,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(strokeWidth, cap = StrokeCap.Butt),
                        )
                    }
```

- [ ] **Step 4: Draw the span-arcs after the green sweep** — the green sweep is the `if (animatedRemaining > 0f) { ... }` block. Immediately **after** that block closes (before the `detourBodies.forEach` block), insert:

```kotlin
                    // Calendar blocks are their own cool-toned layer drawn on top of the
                    // remaining sweep, so the ring itself keeps meaning past/future while hue
                    // means "reserved". A thin core stripe per event, like the focus arcs.
                    busyBlockArcs.forEach { arc ->
                        drawArc(
                            color = colors.busy[arc.colorIndex % colors.busy.size],
                            startAngle = arc.startAngleDegrees,
                            sweepAngle = arc.sweepDegrees,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(strokeWidth * .5f, cap = StrokeCap.Butt),
                        )
                    }

                    busyBlockBodies.forEach { body ->
                        val angleRadians = Math.toRadians(body.angleDegrees.toDouble())
                        val bodyRadius = arcSize.width / 2f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val bodyCenter = center + Offset(
                            x = (kotlin.math.cos(angleRadians) * bodyRadius).toFloat(),
                            y = (kotlin.math.sin(angleRadians) * bodyRadius).toFloat(),
                        )
                        val color = colors.busy[body.colorIndex % colors.busy.size]
                        val half = strokeWidth * (.42f + .32f * body.sizeFraction)
                        drawRoundRect(
                            color = color.copy(alpha = .28f),
                            topLeft = bodyCenter - Offset(half * 1.5f, half * 1.5f),
                            size = Size(half * 3f, half * 3f),
                            cornerRadius = CornerRadius(half * .75f, half * .75f),
                        )
                        drawRoundRect(
                            color = color,
                            topLeft = bodyCenter - Offset(half, half),
                            size = Size(half * 2f, half * 2f),
                            cornerRadius = CornerRadius(half * .5f, half * .5f),
                        )
                    }
```

- [ ] **Step 5: Wire both call sites** — in `DayViewTodayScreen`, both `CountdownCircle(...)` calls (compact ~line 240, wide ~line 294). Add after `detoursTotal = state.detoursTotalToday,`:

```kotlin
                            detoursTotal = state.detoursTotalToday,
                            busyBlockArcs = state.busyBlockArcsState,
                            busyBlockBodies = state.busyBlockBodiesState,
```

(Apply to **both** call sites. Leave the existing `busyArcs = state.busyArcsState` line in place — hover still needs it.)

- [ ] **Step 6: Full suite + commit**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL. (Visual proof comes in Task 8.)

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt
git commit -m "Draw calendar blocks as colored arcs and bodies on the ring"
```

---

## Task 7: Migrate hover/tooltip to blocks with the calendar name

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (hover state ~699-733; tooltip ~972-1013; `HoveredBusyArc` ~1050; `hitTestBusyArc` ~1054-1069; params + call sites)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt` (`arcContainsAngle`)
- Modify: `composeApp/src/commonTest/kotlin/fr/dayview/app/CalendarNetTimeTest.kt` (`arcContainsAngle` test)

**Interfaces:**
- Consumes: `BusyBlockArc` (Task 3).
- Produces: `arcContainsAngle(startAngleDegrees: Float, sweepDegrees: Float, angleDegrees: Float): Boolean` (now primitive-based); hover renders on `BusyBlockArc`, tooltip shows `calendarName`.

- [ ] **Step 1: Rewrite the `arcContainsAngle` test** — in `CalendarNetTimeTest.kt`, replace `arcContainsAngleHandlesSweepAndWraparound` with the primitive form:

```kotlin
    @Test
    fun arcContainsAngleHandlesSweepAndWraparound() {
        // Arc bas-gauche 200°..240°.
        assertEquals(true, arcContainsAngle(200f, 40f, 220f))
        // atan2 renvoie 220° comme -140° : le wraparound doit quand même l'inclure.
        assertEquals(true, arcContainsAngle(200f, 40f, -140f))
        assertEquals(false, arcContainsAngle(200f, 40f, 100f))
        // Arc au sommet, à cheval sur la frontière -90°.
        assertEquals(true, arcContainsAngle(-110f, 40f, -90f))
        assertEquals(false, arcContainsAngle(-110f, 40f, 0f))
    }
```

- [ ] **Step 2: Run, verify fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.CalendarNetTimeTest"`
Expected: FAIL to compile — `arcContainsAngle` still takes a `BusyArc`.

- [ ] **Step 3: Make `arcContainsAngle` primitive** — in `CalendarNetTime.kt`, replace the function:

```kotlin
/** Vrai si l'angle (degrés, convention drawArc) tombe dans le balayage de l'arc, wraparound compris. */
fun arcContainsAngle(startAngleDegrees: Float, sweepDegrees: Float, angleDegrees: Float): Boolean {
    val delta = (((angleDegrees - startAngleDegrees) % 360f) + 360f) % 360f
    return delta <= sweepDegrees
}
```

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.CalendarNetTimeTest"`
Expected: PASS.

- [ ] **Step 5: Point hover state at blocks** — `DayViewTodayScreen.kt`. Change the hover holder and hit test:

Replace `private data class HoveredBusyArc(val arc: BusyArc, val position: Offset)` with:

```kotlin
private data class HoveredBusyArc(val arc: BusyBlockArc, val position: Offset)
```

Replace `hitTestBusyArc`'s signature and last line so it works on blocks:

```kotlin
private fun hitTestBusyArc(
    position: Offset,
    width: Int,
    height: Int,
    busyBlockArcs: List<BusyBlockArc>,
): BusyBlockArc? {
    val cx = width / 2f
    val cy = height / 2f
    val dx = position.x - cx
    val dy = position.y - cy
    val radiusFraction = hypot(dx, dy) / (minOf(width, height) / 2f)
    if (radiusFraction !in 0.70f..1.02f) return null
    val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    return busyBlockArcs.firstOrNull { arcContainsAngle(it.startAngleDegrees, it.sweepDegrees, angle) }
}
```

- [ ] **Step 6: Feed the hit test from the new param** — in the `pointerInput` block, the call `hitTestBusyArc(position, size.width, size.height, busyArcs)` becomes `hitTestBusyArc(position, size.width, size.height, busyBlockArcs)`. Also change the `pointerInput(busyArcs, detourBodies)` key to `pointerInput(busyBlockArcs, detourBodies)`, and the empty-guard `if (busyArcs.isEmpty() && detourBodies.isEmpty())` to `if (busyBlockArcs.isEmpty() && detourBodies.isEmpty())`.

- [ ] **Step 7: Show the calendar name in the tooltip** — in the `hoveredBusy?.let { hovered ->` block, inside the `Column {`, add the calendar-name heading above the titles:

```kotlin
                        Column {
                            if (arc.calendarName.isNotBlank()) {
                                Text(
                                    arc.calendarName,
                                    color = colors.busy[arc.colorIndex % colors.busy.size],
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = .5.sp,
                                )
                            }
                            val titles = arc.titles.filter { it.isNotBlank() }
```

(The rest of the `Column` — the `busy_generic` fallback and the `busy_time_range` line — is unchanged.)

- [ ] **Step 8: Drop the old `busyArcs` param** — remove `busyArcs: List<BusyArc> = emptyList(),` from `CountdownCircle`'s signature, and remove the `busyArcs = state.busyArcsState,` line from **both** call sites (~240 and ~294).

- [ ] **Step 9: Full suite + commit**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL.

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt \
  composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt \
  composeApp/src/commonTest/kotlin/fr/dayview/app/CalendarNetTimeTest.kt
git commit -m "Show calendar name on busy block hover"
```

---

## Task 8: Remove the superseded `busyArcs` API, visual proof, final verification

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt` (remove `BusyArc`, `busyArcs`)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt` (remove `busyArcsState`)
- Modify: `composeApp/src/commonTest/kotlin/fr/dayview/app/CalendarNetTimeTest.kt` (remove `busyArcs*` tests)
- Create (throwaway): `composeApp/src/desktopTest/kotlin/fr/dayview/app/RingBusyScreenshotTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 1-7. After this task, `BusyArc` / `busyArcs` / `busyArcsState` no longer exist.

- [ ] **Step 1: Confirm the old API is unused** —

Run: `grep -rn "busyArcsState\|\\bBusyArc\\b\|\\bbusyArcs(" composeApp/src`
Expected: only the definitions in `CalendarNetTime.kt`, `DayViewController.kt`, and the `busyArcs*` tests. If anything else references them, stop and reconcile.

- [ ] **Step 2: Remove the old code** —
  - `CalendarNetTime.kt`: delete `data class BusyArc(...)` and `fun busyArcs(windowStart, windowEnd, busy): List<BusyArc>`.
  - `DayViewController.kt`: delete the `busyArcsState` getter (the `if (netTimeSettings.enabled) { busyArcs(start, end, busyIntervals) } else emptyList()` block).
  - `CalendarNetTimeTest.kt`: delete `busyArcsProjectAtElapsedFraction`, `busyArcsClipToWindow`, `busyArcsHandleDegenerateWindow`.

- [ ] **Step 3: Full suite green after removal**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Write the throwaway screenshot harness** — create `RingBusyScreenshotTest.kt` to render the ring with two calendars (past + upcoming events) and save a PNG:

```kotlin
package fr.dayview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class RingBusyScreenshotTest {
    @Test
    fun captureRingWithCalendarBlocks() = runComposeUiTest {
        val zone = TimeZone.currentSystemDefault()
        fun at(h: Int, m: Int) = LocalDateTime(2026, 7, 12, h, m).toInstant(zone)
        val now = at(13, 0)
        val (start, end) = dayWindow(now, 8 * 60, 18 * 60, zone)
        val progress = calculateDayProgress(now, 8 * 60, 18 * 60, zone)
        val intervals = listOf(
            BusyInterval(at(9, 0), at(10, 0), listOf("Standup"), calendarId = "work"),
            BusyInterval(at(11, 30), at(12, 30), listOf("Perso"), calendarId = "home"),
            BusyInterval(at(14, 0), at(15, 30), listOf("Atelier"), calendarId = "work"),
            BusyInterval(at(16, 30), at(17, 0), listOf("Sport"), calendarId = "home"),
        )
        val names = mapOf("work" to "Travail", "home" to "Perso")
        setContent {
            DayViewTheme {
                Box(Modifier.requiredSize(520.dp, 620.dp).background(Color(0xFF0B0D12))) {
                    CountdownCircle(
                        progress = progress,
                        showSeconds = false,
                        windowStart = start,
                        windowEnd = end,
                        busyBlockArcs = busyBlockArcs(start, end, intervals, names),
                        busyBlockBodies = busyBlockBodies(start, end, intervals, names),
                    )
                }
            }
        }
        waitForIdle()
        val awt = onRoot().captureToImage().toAwtImage()
        val out = File(System.getProperty("java.io.tmpdir"), "dayview-ring-blocks.png")
        ImageIO.write(awt, "png", out)
        println("RING SCREENSHOT: ${out.absolutePath}")
    }
}
```

- [ ] **Step 5: Render and inspect**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.RingBusyScreenshotTest"`
Then open the path printed as `RING SCREENSHOT: ...` and confirm by eye:
- Past events (before the amber marker) show cool-colored core stripes + blocks on the dark track; the track still reads as "past".
- Upcoming events show cool-colored stripes + blocks on the green; the green still reads as "future" on either side of each stripe.
- The two calendars use two different cool colors.
- If the blocks read better straddling the ring (offset in/out like detour orbs) than riding the line, adjust `bodyRadius` in `CountdownCircle` (e.g. `arcSize.width / 2f + strokeWidth * (.6f - 1.2f * body.sizeFraction)`) and re-render.

- [ ] **Step 6: Delete the harness**

```bash
rm composeApp/src/desktopTest/kotlin/fr/dayview/app/RingBusyScreenshotTest.kt
```

- [ ] **Step 7: Final full suite + commit**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL.

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt \
  composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt \
  composeApp/src/commonTest/kotlin/fr/dayview/app/CalendarNetTimeTest.kt
git commit -m "Remove superseded busy arc rendering path"
```

---

## Self-Review

**Spec coverage:**
- Data model (`calendarId` on `BusyInterval`, both platforms) → Task 1. ✓
- Two-path merge (union for math, within-calendar for rendering) → Task 2 (`mergeBusyIntervalsByCalendar`), math untouched (`mergeBusyIntervals` still used by `calculateNetTime`), locked by Task 1's cross-calendar test. ✓
- `busyCalendars` color assignment → Task 2. ✓
- `busyBlockArcs` / `busyBlockBodies` projections → Task 3. ✓
- Cool-tone `colors.busy` palette (dark + light) → Task 4. ✓
- Hybrid rendering (thin span-arc + rounded-square block), grey pass removed, drawn after the green sweep → Task 6. ✓
- Per-calendar color, block ≠ detour orb (rounded square) → Task 6. ✓
- Hover tag = calendar name → Task 7. ✓
- Cleanup of exploratory `upcomingBusyArcs` — already reverted before planning (not present in the tree); the `busyArcs`/`BusyArc` removal → Task 8. ✓
- Non-goals (real OS colors, widget, persistent labels) — untouched. ✓

**Placeholder scan:** No TBD/TODO; every code step shows complete code; the one visual judgment (block straddle vs ride) has concrete fallback code in Task 8 Step 5.

**Type consistency:** `BusyBlockArc`/`BusyBlockBody` field names are identical in the data-class definitions (Task 3), the render pass (Task 6), the hover path (Task 7), and the harness (Task 8). `arcContainsAngle` is primitive-based from Task 7 onward, and its only caller (`hitTestBusyArc`) is updated in the same task. `colors.busy[colorIndex % size]` indexing is identical in Tasks 6 and 7. `calendarNamesById` (Task 5) feeds the `calendarNames` param of Task 3's functions.
