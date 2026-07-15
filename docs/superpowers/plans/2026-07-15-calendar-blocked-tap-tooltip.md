# Tap a Blocked Calendar Zone to Show Its Label (Mobile) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On touch, a single tap on a blocked (busy) zone of the ring shows the same busy-label tooltip (`HoverTooltip`) that mouse hover already shows on desktop.

**Architecture:** Merge tap detection into the existing long-press-scrub gesture on the ring (`scrubModifier`) using the standard Compose "tap vs long press" pattern (`withTimeout` + `waitForUpOrCancellation`). A tap resolves the arc under the finger via the existing `hitTestBusyArc`, then drives the existing `hoveredBusy` state — the same state mouse hover already uses — through a new pure helper `nextHoveredBusyOnTap`. No data-model changes.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform 1.11.1, `kotlin.test` (JUnit on desktop).

## Global Constraints

- ktlint is enforced — no unused imports; run `./gradlew ktlintCheck` before committing.
- Design code (spec): `docs/superpowers/specs/2026-07-15-calendar-blocked-tap-tooltip-design.md`.
- Scope is blocked (busy) zones only, touch only. Desktop mouse hover behaviour must stay unchanged.
- No `BusyBlockArc` model change (it already carries `titles`, `calendarName`, `start`, `end`).
- Commit messages in English; never reference Claude/Anthropic/AI.

---

## File Structure

- `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt`
  - `HoveredBusyArc` — change `private` → `internal` so the test can construct it.
  - `nextHoveredBusyOnTap(...)` — new `internal` pure function (tap → next `hoveredBusy`).
  - `scrubModifier` (lines ~1007-1033) — merge tap detection into the single `awaitEachGesture`.
  - imports — drop `awaitLongPressOrCancellation`; add `waitForUpOrCancellation` and `PointerEventTimeoutCancellationException`.
- `composeApp/src/desktopTest/kotlin/fr/dayview/app/BlockedZoneTapTest.kt` — new unit test for `nextHoveredBusyOnTap`.

---

### Task 1: Pure tap-to-state helper `nextHoveredBusyOnTap`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (the `private data class HoveredBusyArc` at line 1576; add the new function nearby)
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/BlockedZoneTapTest.kt`

**Interfaces:**
- Consumes: `BusyBlockArc` (from `fr.dayview.app.BusyBlockArc` in `:core`, a public data class with value equality), `androidx.compose.ui.geometry.Offset`.
- Produces:
  - `internal data class HoveredBusyArc(val arc: BusyBlockArc, val position: Offset)` (visibility widened from `private`).
  - `internal fun nextHoveredBusyOnTap(current: HoveredBusyArc?, tapped: BusyBlockArc?, position: Offset): HoveredBusyArc?`

- [ ] **Step 1: Write the failing test**

Create `composeApp/src/desktopTest/kotlin/fr/dayview/app/BlockedZoneTapTest.kt`:

```kotlin
package fr.dayview.app

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

private fun arc(name: String, colorIndex: Int = 0): BusyBlockArc =
    BusyBlockArc(
        startAngleDegrees = 0f,
        sweepDegrees = 30f,
        colorIndex = colorIndex,
        titles = listOf(name),
        calendarName = name,
        start = Instant.fromEpochMilliseconds(0L),
        end = Instant.fromEpochMilliseconds(3_600_000L),
    )

class BlockedZoneTapTest {
    @Test
    fun tappingEmptyRingClosesTheTooltip() {
        val current = HoveredBusyArc(arc("Work"), Offset(10f, 10f))
        assertNull(nextHoveredBusyOnTap(current, tapped = null, position = Offset(5f, 5f)))
    }

    @Test
    fun tappingTheShownZoneClosesTheTooltip() {
        val work = arc("Work")
        val current = HoveredBusyArc(work, Offset(10f, 10f))
        assertNull(nextHoveredBusyOnTap(current, tapped = work, position = Offset(12f, 12f)))
    }

    @Test
    fun tappingAnotherZoneSwitchesTheTooltip() {
        val work = arc("Work", colorIndex = 0)
        val gym = arc("Gym", colorIndex = 1)
        val current = HoveredBusyArc(work, Offset(10f, 10f))
        val next = nextHoveredBusyOnTap(current, tapped = gym, position = Offset(40f, 40f))
        assertEquals(HoveredBusyArc(gym, Offset(40f, 40f)), next)
    }

    @Test
    fun tappingAZoneWithNothingShownOpensTheTooltip() {
        val gym = arc("Gym")
        val next = nextHoveredBusyOnTap(current = null, tapped = gym, position = Offset(40f, 40f))
        assertEquals(HoveredBusyArc(gym, Offset(40f, 40f)), next)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.BlockedZoneTapTest"`
Expected: FAIL — compilation error, `nextHoveredBusyOnTap` unresolved and `HoveredBusyArc` not accessible (still `private`).

- [ ] **Step 3: Widen `HoveredBusyArc` and add the helper**

In `DayViewTodayScreen.kt`, change the existing declaration at line 1576 from:

```kotlin
private data class HoveredBusyArc(val arc: BusyBlockArc, val position: Offset)
```

to:

```kotlin
internal data class HoveredBusyArc(val arc: BusyBlockArc, val position: Offset)

/**
 * Next [HoveredBusyArc] state after a touch tap on the ring. Tapping another zone switches to
 * it, tapping the shown zone or an empty part of the ring closes the tooltip. Kept pure so the
 * tap/close rules are unit-testable without driving a gesture.
 */
internal fun nextHoveredBusyOnTap(
    current: HoveredBusyArc?,
    tapped: BusyBlockArc?,
    position: Offset,
): HoveredBusyArc? = when {
    tapped == null -> null
    current?.arc == tapped -> null
    else -> HoveredBusyArc(tapped, position)
}
```

Leave `private data class HoveredDetourBody(...)` on the following line unchanged.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.BlockedZoneTapTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/BlockedZoneTapTest.kt
git commit -m "Add pure tap-to-state helper for blocked-zone tooltip"
```

---

### Task 2: Wire the tap gesture into the ring

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt`
  - imports (lines 10-13 region)
  - `scrubModifier` gesture (lines ~1007-1033)

**Interfaces:**
- Consumes: `nextHoveredBusyOnTap`, `HoveredBusyArc` (Task 1); existing `hitTestBusyArc(position, width, height, busyBlockArcs): BusyBlockArc?` (line ~1610); existing `hoveredBusy` state (line 955); existing `busyBlockArcs`, `detourBodies`, `focusArcs` params; `AwaitPointerEventScope.viewConfiguration`, `size`.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Fix imports**

In `DayViewTodayScreen.kt`, delete this line (line 12, no longer used after the refactor):

```kotlin
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
```

Add these imports in their alphabetical positions among the existing `androidx.compose.foundation.gestures.*` / `androidx.compose.ui.input.pointer.*` import groups:

```kotlin
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
```

(Keep `import androidx.compose.foundation.gestures.detectTapGestures` — still used at line ~1673.)

- [ ] **Step 2: Replace the `scrubModifier` gesture body**

Replace the whole `scrubModifier` block (currently lines ~1007-1033), i.e. from
`val scrubModifier = circleModifier.pointerInput(...) {` through its closing `}`, with:

```kotlin
            val scrubModifier = circleModifier.pointerInput(busyBlockArcs, detourBodies, focusArcs) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.type != PointerType.Touch) return@awaitEachGesture
                    fun angleOf(pos: Offset): Float {
                        val dx = pos.x - size.width / 2f
                        val dy = pos.y - size.height / 2f
                        val raw = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                        return normalizeRingAngle(raw)
                    }
                    // A quick release before the long-press timeout is a tap; the timeout firing
                    // (PointerEventTimeoutCancellationException) is a long press → ring scrub.
                    var longPress = false
                    val up = try {
                        withTimeout(viewConfiguration.longPressTimeoutMillis) {
                            waitForUpOrCancellation()
                        }
                    } catch (_: PointerEventTimeoutCancellationException) {
                        longPress = true
                        null
                    }
                    when {
                        longPress -> {
                            try {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                scrubAngle = angleOf(down.position)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    scrubAngle = angleOf(change.position)
                                    change.consume()
                                }
                            } finally {
                                scrubAngle = null
                            }
                        }
                        up != null -> {
                            // Tap: reveal / switch / dismiss the busy-label tooltip.
                            val tapped = hitTestBusyArc(up.position, size.width, size.height, busyBlockArcs)
                            hoveredBusy = nextHoveredBusyOnTap(hoveredBusy, tapped, up.position)
                            up.consume()
                        }
                        // else: waitForUpOrCancellation returned null (gesture cancelled) → do nothing.
                    }
                }
            }
```

Notes for the implementer:
- `withTimeout` and `waitForUpOrCancellation` are members/extensions of `AwaitPointerEventScope` (the receiver of `awaitEachGesture`); `viewConfiguration` and `size` are the same scope properties the old code already used.
- The long-press branch is the previous scrub loop verbatim, except the initial angle now seeds from `down.position` (the finger has barely moved when the timeout fires; the loop corrects it on the next move) instead of the old `pressed.position`.

- [ ] **Step 3: Lint and build the shared/desktop target**

Run: `./gradlew ktlintCheck :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL, no ktlint violations, no unused-import warnings.

- [ ] **Step 4: Run the existing today-screen and tap tests**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.BlockedZoneTapTest" --tests "fr.dayview.app.TodayScreenTest"`
Expected: PASS. (The gesture wiring itself has no UI test — touch long-press/tap is not reliably simulable in `desktopTest`; it is thin glue over the already-tested `hitTestBusyArc` and `nextHoveredBusyOnTap`.)

- [ ] **Step 5: Manual verification on a touch build**

Build/install the Android debug app and confirm on device/emulator:
- Tap a blocked arc → busy-label tooltip appears (calendar name + title(s) + time range).
- Tap the same arc again, or an empty part of the ring → tooltip dismisses.
- Tap a different arc → tooltip switches to it.
- Long-press and drag still shows the scrub readout as before (unchanged).

Run: `./gradlew :composeApp:installDebug`

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt
git commit -m "Show busy-label tooltip on tapping a blocked calendar zone (touch)"
```

---

## Final verification

- [ ] Run the full gate:

Run: `./gradlew ktlintCheck :core:jvmTest :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, no errors or stderr.

---

## Self-Review

- **Spec coverage:** tap → `HoverTooltip` via `hoveredBusy` (Task 2); merged tap/long-press gesture (Task 2, Step 2); pure `nextHoveredBusyOnTap` with switch/close rules + unit tests (Task 1); busy-only, touch-only, desktop hover untouched (Task 2 leaves the mouse block at lines 970-1006 unchanged); no model change (confirmed — `BusyBlockArc` unchanged). Covered.
- **Placeholder scan:** none — all steps show full code and exact commands.
- **Type consistency:** `HoveredBusyArc(arc, position)`, `nextHoveredBusyOnTap(current, tapped, position)`, and `hitTestBusyArc(position, width, height, busyBlockArcs)` are used identically across Tasks 1 and 2.
