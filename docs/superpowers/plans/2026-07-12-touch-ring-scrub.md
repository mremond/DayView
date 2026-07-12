# Touch Long-Press Ring Scrub Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a touch user long-press and drag a mark around the countdown ring, reading out everything on the ring (time of day, calendar busy, detour, focus, "now") at the mark's angle — iPod-wheel style, no precise targeting.

**Architecture:** A pure `ringReadoutAt(...)` function maps a ring angle to a `RingReadout` model listing every layer present there, reusing the existing angle helpers (`angleToInstant`, `angularDistanceToArc`, `arcContainsAngle`) and a new public `detourBodyAtAngle`. `CountdownCircle` gains a touch-only long-press-drag `pointerInput` that tracks the finger's angle into a `scrubAngle` state, draws a mark on the ring, and shows a fixed bottom-center readout overlay while armed. Mouse hover tooltips are untouched.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, kotlin.test (commonTest), kotlinx.datetime.

## Global Constraints

- ktlint is enforced — run `./gradlew ktlintCheck` before committing; `ktlintFormat` auto-fixes.
- Full gate before any commit: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest` (must pass with no errors/stderr).
- Pure logic + tests live in `commonMain` / `commonTest` (follow `CalendarNetTimeTest`, `DetoursTest`).
- Do NOT assert `stringResource` text in Compose UI tests — it does not resolve under the harness. Test the pure `RingReadout` model instead.
- Commit messages describe the change only — never reference Claude/Anthropic/AI, no Co-Authored-By, no test/verification sections.
- Angle convention throughout: `drawArc` degrees, `-90°` = window start, clockwise.

---

### Task 1: `detourBodyAtAngle` — angle-only detour hit-test

Expose the detour body under a given angle, reusing the exact tolerance the mouse hover uses (`hitTestDetourBody`), minus the radius check — so the radius-independent scrub can find detours by angle alone.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/Detours.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DetoursTest.kt`

**Interfaces:**
- Consumes: `DetourBody` (fields `angleDegrees: Float`, `sizeFraction: Float`), private `angularDistance(a: Float, b: Float): Float` (same file), `detourBodies(windowStart, windowEnd, episodes)`, `DetourEpisode`.
- Produces: `fun detourBodyAtAngle(bodies: List<DetourBody>, angleDegrees: Float): DetourBody?`

- [ ] **Step 1: Write the failing test**

Add to `DetoursTest.kt` (the file already has `private fun t(ms: Long): Instant`):

```kotlin
@Test
fun detourBodyAtAngleFindsBodyNearItsMidpointAngle() {
    val windowStart = t(0L)
    val windowEnd = t(24L * 60 * 60 * 1000) // 24 h window: 15°/h, midpoint drives the angle
    // A 60-min detour centred at 06:00 -> midpoint fraction .25 -> angle -90 + 90 = 0°.
    val episodes = listOf(DetourEpisode(t(5L * 3_600_000), t(7L * 3_600_000), "Slack"))
    val bodies = detourBodies(windowStart, windowEnd, episodes)
    assertEquals(1, bodies.size)
    val body = bodies.first()
    // At the exact midpoint angle it is found; far away (180° across) it is not.
    assertEquals(body, detourBodyAtAngle(bodies, body.angleDegrees))
    assertNull(detourBodyAtAngle(bodies, body.angleDegrees + 180f))
}
```

Add imports if missing: `import kotlin.test.assertNull`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DetoursTest"`
Expected: FAIL — `detourBodyAtAngle` unresolved reference.

- [ ] **Step 3: Write minimal implementation**

In `Detours.kt`, directly after `hitTestDetourBody(...)`, add:

```kotlin
/**
 * The detour body whose angular position is closest to [angleDegrees], within the same
 * size-scaled tolerance as [hitTestDetourBody] but with no radius constraint — for the
 * radius-independent touch scrub. Null if none is close enough.
 */
fun detourBodyAtAngle(bodies: List<DetourBody>, angleDegrees: Float): DetourBody? =
    bodies
        .minByOrNull { angularDistance(it.angleDegrees, angleDegrees) }
        ?.takeIf { angularDistance(it.angleDegrees, angleDegrees) <= 7f + 5f * it.sizeFraction }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DetoursTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/Detours.kt composeApp/src/commonTest/kotlin/fr/dayview/app/DetoursTest.kt
git commit -m "Add angle-only detour hit-test for the ring scrub"
```

---

### Task 2: `RingReadout` + `ringReadoutAt` pure function

Compute every ring layer present at one angle. Pure, no Compose — fully unit-tested.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/RingScrub.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/RingScrubTest.kt`

**Interfaces:**
- Consumes: `BusyBlockArc` (`startAngleDegrees`, `sweepDegrees`, `colorIndex`, `titles`, `calendarName`), `DetourBody` (`start`, `end`, `motif`, `colorIndex`), `FocusArc` (`startAngleDegrees`, `sweepDegrees`), `angleToInstant`, `angularDistanceToArc`, `arcContainsAngle` (all in `CalendarNetTime.kt`), `detourBodyAtAngle` (Task 1).
- Produces:
  - `data class RingReadout(val time: Instant, val isNow: Boolean, val busy: BusyBlockArc?, val detour: DetourBody?, val focus: Boolean)`
  - `fun ringReadoutAt(angleDegrees: Float, windowStart: Instant, windowEnd: Instant, busyBlockArcs: List<BusyBlockArc>, detourBodies: List<DetourBody>, focusArcs: List<FocusArc>, momentAngleDegrees: Float?): RingReadout`

- [ ] **Step 1: Write the failing test**

Create `composeApp/src/commonTest/kotlin/fr/dayview/app/RingScrubTest.kt`:

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private fun ms(ms: Long): Instant = Instant.fromEpochMilliseconds(ms)

class RingScrubTest {
    private val windowStart = ms(0L)
    private val windowEnd = ms(24L * 60 * 60 * 1000) // 24 h: angle = -90 + 360 * fraction

    @Test
    fun emptyRingReportsOnlyTimeOfDay() {
        val r = ringReadoutAt(
            angleDegrees = 0f, // fraction .25 -> 06:00
            windowStart = windowStart,
            windowEnd = windowEnd,
            busyBlockArcs = emptyList(),
            detourBodies = emptyList(),
            focusArcs = emptyList(),
            momentAngleDegrees = null,
        )
        assertEquals(ms(6L * 3_600_000), r.time)
        assertFalse(r.isNow)
        assertNull(r.busy)
        assertNull(r.detour)
        assertFalse(r.focus)
    }

    @Test
    fun busyArcUnderAngleIsReported() {
        val arc = BusyBlockArc(
            startAngleDegrees = -5f,
            sweepDegrees = 10f,
            colorIndex = 0,
            titles = listOf("Standup"),
            calendarName = "Work",
        )
        val r = ringReadoutAt(
            0f, windowStart, windowEnd,
            busyBlockArcs = listOf(arc),
            detourBodies = emptyList(),
            focusArcs = emptyList(),
            momentAngleDegrees = null,
        )
        assertEquals(arc, r.busy)
    }

    @Test
    fun detourUnderAngleIsReported() {
        val episodes = listOf(DetourEpisode(ms(5L * 3_600_000), ms(7L * 3_600_000), "Slack"))
        val bodies = detourBodies(windowStart, windowEnd, episodes)
        val body = bodies.first()
        val r = ringReadoutAt(
            body.angleDegrees, windowStart, windowEnd,
            busyBlockArcs = emptyList(),
            detourBodies = bodies,
            focusArcs = emptyList(),
            momentAngleDegrees = null,
        )
        assertEquals(body, r.detour)
    }

    @Test
    fun focusArcUnderAngleSetsFocus() {
        val focus = FocusArc(startAngleDegrees = -10f, sweepDegrees = 20f)
        val r = ringReadoutAt(
            0f, windowStart, windowEnd,
            busyBlockArcs = emptyList(),
            detourBodies = emptyList(),
            focusArcs = listOf(focus),
            momentAngleDegrees = null,
        )
        assertTrue(r.focus)
    }

    @Test
    fun angleNearMomentMarkerIsNow() {
        val near = ringReadoutAt(
            0f, windowStart, windowEnd,
            emptyList(), emptyList(), emptyList(),
            momentAngleDegrees = 1f,
        )
        assertTrue(near.isNow)
        val far = ringReadoutAt(
            0f, windowStart, windowEnd,
            emptyList(), emptyList(), emptyList(),
            momentAngleDegrees = 90f,
        )
        assertFalse(far.isNow)
    }

    @Test
    fun overlappingLayersAreAllReported() {
        val arc = BusyBlockArc(-5f, 10f, 0, listOf("Standup"), "Work")
        val focus = FocusArc(-10f, 20f)
        val episodes = listOf(DetourEpisode(ms(5L * 3_600_000), ms(7L * 3_600_000), "Slack"))
        val bodies = detourBodies(windowStart, windowEnd, episodes) // midpoint angle ~0°
        val r = ringReadoutAt(
            0f, windowStart, windowEnd,
            busyBlockArcs = listOf(arc),
            detourBodies = bodies,
            focusArcs = listOf(focus),
            momentAngleDegrees = 0f,
        )
        assertEquals(arc, r.busy)
        assertEquals(bodies.first(), r.detour)
        assertTrue(r.focus)
        assertTrue(r.isNow)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.RingScrubTest"`
Expected: FAIL — `ringReadoutAt` / `RingReadout` unresolved reference.

- [ ] **Step 3: Write minimal implementation**

Create `composeApp/src/commonMain/kotlin/fr/dayview/app/RingScrub.kt`:

```kotlin
package fr.dayview.app

import kotlin.time.Instant

/**
 * Everything the countdown ring shows at one angle, for the touch scrub readout.
 * Overlaps are kept: [time] is always set, plus any layer under the angle.
 */
data class RingReadout(
    val time: Instant,
    val isNow: Boolean,
    val busy: BusyBlockArc?,
    val detour: DetourBody?,
    val focus: Boolean,
)

private const val NOW_TOLERANCE_DEGREES = 3f
private const val BUSY_TOLERANCE_DEGREES = 5f

/**
 * Read every ring layer present at [angleDegrees] (drawArc convention, -90° = window start).
 * [momentAngleDegrees] is null before the day starts / after it ends. Busy and detour use
 * the same tolerances as the mouse hover hit-tests so thin arcs and small bodies stay
 * reachable; focus is a plain containment test.
 */
fun ringReadoutAt(
    angleDegrees: Float,
    windowStart: Instant,
    windowEnd: Instant,
    busyBlockArcs: List<BusyBlockArc>,
    detourBodies: List<DetourBody>,
    focusArcs: List<FocusArc>,
    momentAngleDegrees: Float?,
): RingReadout {
    val busy = busyBlockArcs
        .minByOrNull { angularDistanceToArc(it.startAngleDegrees, it.sweepDegrees, angleDegrees) }
        ?.takeIf {
            angularDistanceToArc(it.startAngleDegrees, it.sweepDegrees, angleDegrees) <= BUSY_TOLERANCE_DEGREES
        }
    val detour = detourBodyAtAngle(detourBodies, angleDegrees)
    val focus = focusArcs.any { arcContainsAngle(it.startAngleDegrees, it.sweepDegrees, angleDegrees) }
    val isNow = momentAngleDegrees != null &&
        angularDistanceToArc(momentAngleDegrees, 0f, angleDegrees) <= NOW_TOLERANCE_DEGREES
    return RingReadout(
        time = angleToInstant(angleDegrees, windowStart, windowEnd),
        isNow = isNow,
        busy = busy,
        detour = detour,
        focus = focus,
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.RingScrubTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/RingScrub.kt composeApp/src/commonTest/kotlin/fr/dayview/app/RingScrubTest.kt
git commit -m "Add ringReadoutAt: read every ring layer at one angle"
```

---

### Task 3: Readout string + touch scrub gesture, mark, and overlay in `CountdownCircle`

Wire the gesture, draw the mark, and render the fixed bottom-center readout. Compose UI wiring is verified by the build + gate (touch long-press injection is not reliably testable in the harness); the readout content is already covered by Task 2.

**Files:**
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Modify: `composeApp/src/commonMain/composeResources/values-fr/strings.xml`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt`

**Interfaces:**
- Consumes: `ringReadoutAt` + `RingReadout` (Task 2); existing `currentMomentAngleDegrees`, `formatClockHm`, `formatDurationHm`, `LocalDayViewColors`, `LocalUses24HourClock`, `Res.string.*`.
- Produces: no cross-task interface (terminal task).

- [ ] **Step 1: Add the "now" string resource**

In `composeApp/src/commonMain/composeResources/values/strings.xml`, add near the busy strings (after `busy_time_range`):

```xml
    <string name="scrub_now">Now</string>
```

In `composeApp/src/commonMain/composeResources/values-fr/strings.xml`, add the matching French entry in the same relative spot:

```xml
    <string name="scrub_now">Maintenant</string>
```

- [ ] **Step 2: Add imports**

In `DayViewTodayScreen.kt`, add these imports (keep them ktlint-ordered with the existing block):

```kotlin
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlin.math.atan2
```

Also ensure these are present (add only if the file does not already import them): `androidx.compose.ui.geometry.Offset`, `kotlin.math.cos`, `kotlin.math.sin`. (`Offset`, `cos`, `sin`, and `Math.toRadians`/`toDegrees` are already used in this file.)

- [ ] **Step 3: Add scrub state and haptic handle**

In `CountdownCircle`, next to the existing hover state (`var hoveredBusy ...` / `var hoveredDetour ...` around line 744), add:

```kotlin
var scrubAngle by remember { mutableStateOf<Float?>(null) }
val haptic = LocalHapticFeedback.current
```

- [ ] **Step 4: Attach the touch scrub gesture to the circle**

The circle is wrapped by `circleModifier`. Chain a second, touch-only `pointerInput` onto whatever `circleModifier` resolves to, so it applies in both the empty-ring and busy/detour branches. Change the block that builds `circleModifier` (lines ~757-790) so the result always has the scrub gesture appended. Concretely, after the existing `val circleModifier = if (...) { Modifier.size(circleSize) } else { Modifier.size(circleSize).pointerInput(...) { ... } }`, add:

```kotlin
val scrubModifier = circleModifier.pointerInput(busyBlockArcs, detourBodies, focusArcs) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.type != PointerType.Touch) return@awaitEachGesture
        val pressed = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        fun angleOf(pos: Offset): Float {
            val dx = pos.x - size.width / 2f
            val dy = pos.y - size.height / 2f
            return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        }
        scrubAngle = angleOf(pressed.position)
        pressed.consume()
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            scrubAngle = angleOf(change.position)
            change.consume()
        }
        scrubAngle = null
    }
}
```

Then change the `Box(circleModifier, ...)` line (~791) to use `scrubModifier`:

```kotlin
Box(scrubModifier, contentAlignment = Alignment.Center) {
```

- [ ] **Step 5: Draw the scrub mark on the ring**

Inside the `Canvas` draw block, at the very end (after the busy/detour drawing, before the closing brace of the Canvas lambda ~line 960), add:

```kotlin
scrubAngle?.let { angle ->
    val angleRadians = Math.toRadians(angle.toDouble())
    val arcRadius = arcSize.width / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    val markCenter = center + Offset(
        (kotlin.math.cos(angleRadians) * arcRadius).toFloat(),
        (kotlin.math.sin(angleRadians) * arcRadius).toFloat(),
    )
    drawCircle(color = colors.cloud.copy(alpha = .22f), radius = strokeWidth * 1.15f, center = markCenter)
    drawCircle(color = colors.cloud, radius = strokeWidth * .55f, center = markCenter)
}
```

- [ ] **Step 6: Render the fixed bottom-center readout overlay**

Inside the same `Box(scrubModifier, ...)` (the one holding the Canvas and the hover tooltips), after the `hoveredDetour?.let { ... }` block (~line 1155) and before the Box closes, add:

```kotlin
scrubAngle?.let { angle ->
    val momentAngle = if (progress.hasStarted && !progress.isFinished) {
        currentMomentAngleDegrees(animatedRemaining)
    } else {
        null
    }
    val readout = ringReadoutAt(
        angle, windowStart, windowEnd, busyBlockArcs, detourBodies, focusArcs, momentAngle,
    )
    RingScrubReadout(
        readout = readout,
        uses24Hour = uses24Hour,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
    )
}
```

- [ ] **Step 7: Add the `RingScrubReadout` composable**

Directly after `CountdownCircle` closes (after line ~1159, before `private data class HoveredBusyArc`), add:

```kotlin
/**
 * Fixed readout for the touch ring scrub: the time of day under the mark, a "now" pill when
 * the mark sits on the current moment, then any calendar-busy / detour / focus layer present
 * at that angle, each in its layer colour. Content is computed by [ringReadoutAt].
 */
@Composable
private fun RingScrubReadout(
    readout: RingReadout,
    uses24Hour: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDayViewColors.current
    Box(
        modifier = modifier
            .background(colors.panel, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatClockHm(readout.time, use24Hour = uses24Hour),
                    color = colors.cloud,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (readout.isNow) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(Res.string.scrub_now).uppercase(),
                        color = colors.amber,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                }
            }
            readout.busy?.let { arc ->
                val titles = arc.titles.filter { it.isNotBlank() }
                Text(
                    if (titles.isEmpty()) stringResource(Res.string.busy_generic) else titles.joinToString(" · "),
                    color = colors.busy[arc.colorIndex % colors.busy.size],
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            readout.detour?.let { body ->
                Text(
                    stringResource(
                        Res.string.detour_time_range,
                        formatClockHm(body.start, use24Hour = uses24Hour),
                        formatClockHm(body.end, use24Hour = uses24Hour),
                        formatDurationHm(body.end - body.start),
                    ),
                    color = colors.detours[body.colorIndex % colors.detours.size],
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (readout.focus) {
                Text(
                    stringResource(Res.string.focus_section),
                    color = colors.mint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}
```

Note: verify `colors.detours` and `colors.busy` are the palette lists used in the existing detour/busy drawing (they are — see the `colors.detours[body.colorIndex % ...]` and `colors.busy[arc.colorIndex % ...]` usages already in this file). If the detour body drawing uses a different accessor, match it. `Row`, `Spacer`, `Column`, `width`, `background`, `RoundedCornerShape`, `stringResource` are already imported (used elsewhere in this file).

- [ ] **Step 8: Run the full gate**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: PASS, no ktlint errors, no stderr. If ktlint flags import order/formatting, run `./gradlew ktlintFormat` and re-run the gate.

- [ ] **Step 9: Manual verification**

Run: `./gradlew :composeApp:run`
- With a mouse: hover tooltips behave exactly as before (no regression).
- Simulate touch is not available on desktop with a mouse; on an Android device/emulator (`./gradlew :composeApp:installDebug`) long-press inside the ring: a mark appears with a haptic tick, dragging sweeps it around the ring by angle regardless of finger distance from center, the bottom-center panel shows the time (and busy/detour/focus/now when present), and lifting the finger clears both.

- [ ] **Step 10: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt \
        composeApp/src/commonMain/composeResources/values/strings.xml \
        composeApp/src/commonMain/composeResources/values-fr/strings.xml
git commit -m "Add touch long-press scrub of the countdown ring"
```

---

## Notes for the implementer

- **`awaitLongPressOrCancellation`** lives in `androidx.compose.foundation.gestures`. It suspends until the long-press timeout (platform default, ~500 ms) and returns the `PointerInputChange`, or `null` if the pointer is released / moves beyond the touch slop / is cancelled first. That null path is the "user tapped or dragged without holding" case — we simply do not arm.
- **Touch-only** is enforced by the `down.type != PointerType.Touch` early return, so mouse gestures fall through to the existing hover `pointerInput` untouched.
- **Radius independence** is inherent: `angleOf` uses only `atan2(dy, dx)`, never the distance from center — the iPod-wheel feel.
- **Panel position** is one line: `Alignment.BottomCenter` in Step 6. Switch to `Alignment.TopCenter` to move it up.
