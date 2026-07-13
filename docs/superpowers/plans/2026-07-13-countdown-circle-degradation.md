# Countdown Circle Content Degradation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the countdown ring's interior degrade gracefully on small rings — secondary rows scale with the numerals, then collapse/drop by priority so content never overflows the dial.

**Architecture:** Add a pure, unit-tested helper `countdownInterior(...)` that, given the ring diameter and counter scale, decides which secondary rows survive and whether Net renders in compact form. `CountdownCircle` calls it once and guards each row with the returned flags while multiplying the currently-fixed font sizes and spacers by `counterScale`.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, `kotlin.test` (commonTest).

## Global Constraints

- JDK 21 toolchain; run `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest` before committing (must pass with no stderr).
- ktlint enforced — run `./gradlew ktlintFormat` if it complains.
- Commit messages: English, describe the change only. **Never** reference Claude/Anthropic/AI, and **never** reference internal docs (specs/plans). No test-plan or verification sections in commits.
- Priority ranking (keep → drop first): numerals(1) → Net(2) → Détours(3) → Focus(4) → occupé/busy(5) → clean-session pips(6). Numerals never drop.

## File Structure

- `composeApp/src/commonMain/kotlin/fr/dayview/app/CountdownScaling.kt` — add `CountdownInterior` data class + `countdownInterior(...)` helper next to the existing pure scaling helpers.
- `composeApp/src/commonTest/kotlin/fr/dayview/app/CountdownInteriorTest.kt` — new pure unit tests for the helper.
- `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt` — add `NetRemaining` and `Detours` tags.
- `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` — wire the helper into `CountdownCircle`'s center `Column` (rows 4–7).

---

### Task 1: Pure interior-layout helper

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/CountdownScaling.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/CountdownInteriorTest.kt`

**Interfaces:**
- Consumes: nothing (pure function over primitives + `Dp`).
- Produces:
  - `internal data class CountdownInterior(val showNet: Boolean, val netCompact: Boolean, val showBusy: Boolean, val showFocus: Boolean, val showDetours: Boolean, val showAccolades: Boolean)`
  - `internal fun countdownInterior(circleSize: Dp, counterScale: Float, showSeconds: Boolean, hasNet: Boolean, hasBusy: Boolean, hasFocus: Boolean, hasDetours: Boolean, hasAccolades: Boolean): CountdownInterior`

- [ ] **Step 1: Write the failing tests**

Create `composeApp/src/commonTest/kotlin/fr/dayview/app/CountdownInteriorTest.kt`:

```kotlin
package fr.dayview.app

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CountdownInteriorTest {
    private fun all(circleSize: Float, scale: Float, showSeconds: Boolean = false) =
        countdownInterior(
            circleSize = circleSize.dp,
            counterScale = scale,
            showSeconds = showSeconds,
            hasNet = true,
            hasBusy = true,
            hasFocus = true,
            hasDetours = true,
            hasAccolades = true,
        )

    @Test
    fun largeRingShowsEverythingAtFullSize() {
        val interior = all(circleSize = 400f, scale = 1.0f)
        assertTrue(interior.showNet)
        assertTrue(interior.showBusy)
        assertTrue(interior.showFocus)
        assertTrue(interior.showDetours)
        assertTrue(interior.showAccolades)
        assertFalse(interior.netCompact)
    }

    @Test
    fun tinyRingKeepsOnlyNumerals() {
        val interior = all(circleSize = 90f, scale = 0.45f)
        assertFalse(interior.showNet)
        assertFalse(interior.showBusy)
        assertFalse(interior.showFocus)
        assertFalse(interior.showDetours)
        assertFalse(interior.showAccolades)
    }

    @Test
    fun smallRingCullsBottomUpByPriority() {
        // A compact ring keeps the two highest-priority rows (Net, Détours)
        // and drops Focus, busy sub-line, and pips.
        val interior = all(circleSize = 160f, scale = 0.72f)
        assertTrue(interior.showNet)
        assertTrue(interior.showDetours)
        assertFalse(interior.showFocus)
        assertFalse(interior.showBusy)
        assertFalse(interior.showAccolades)
        // Below the compact threshold Net sheds its label.
        assertTrue(interior.netCompact)
    }

    @Test
    fun busySubLineRequiresNet() {
        val interior = countdownInterior(
            circleSize = 400.dp,
            counterScale = 1.0f,
            showSeconds = false,
            hasNet = false,
            hasBusy = true,
            hasFocus = false,
            hasDetours = false,
            hasAccolades = false,
        )
        assertFalse(interior.showNet)
        assertFalse(interior.showBusy)
    }

    @Test
    fun netStaysFullSizeOnLargeRing() {
        assertFalse(all(circleSize = 400f, scale = 1.0f).netCompact)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.CountdownInteriorTest"`
Expected: FAIL — `countdownInterior` / `CountdownInterior` unresolved reference.

- [ ] **Step 3: Implement the helper**

Append to `composeApp/src/commonMain/kotlin/fr/dayview/app/CountdownScaling.kt`:

```kotlin
/**
 * Which secondary rows survive inside the ring and whether Net renders in its compact,
 * label-less form. Decided purely from the ring diameter and counter scale so the interior
 * degrades the same way across phone, mini/compact desktop, and Supernote — no per-device
 * breakpoints. Rows are culled bottom-up by priority (pips → busy → Focus → Détours), the
 * countdown numerals themselves are never part of this budget.
 *
 * The height constants are empirical (they approximate each scaled row's rendered height in
 * dp); tune them by eye with `./gradlew :composeApp:run`, not by changing the algorithm.
 */
internal data class CountdownInterior(
    val showNet: Boolean,
    val netCompact: Boolean,
    val showBusy: Boolean,
    val showFocus: Boolean,
    val showDetours: Boolean,
    val showAccolades: Boolean,
)

// Fraction of the ring diameter usable as vertical room for the centered content column: a
// centered text column ~0.7·d wide leaves ~0.64·d of vertical chord inside the circle.
private const val INTERIOR_CONTENT_HEIGHT_FRACTION = 0.64f
// At or below this counter scale the "Net" label is dropped so the value stays on one line.
private const val NET_COMPACT_SCALE_THRESHOLD = 0.8f

// Unscaled row-height estimates (dp). Numerals block reserved first, then secondary rows.
private const val RESERVE_HEADER = 16f
private const val RESERVE_HEADER_SPACER = 8f
private const val RESERVE_NUMERALS = 62f
private const val RESERVE_SECONDS = 18f
private const val ROW_NET = 20f
private const val ROW_DETOURS = 19f
private const val ROW_FOCUS = 19f
private const val ROW_BUSY = 17f
private const val ROW_ACCOLADES = 34f

internal fun countdownInterior(
    circleSize: Dp,
    counterScale: Float,
    showSeconds: Boolean,
    hasNet: Boolean,
    hasBusy: Boolean,
    hasFocus: Boolean,
    hasDetours: Boolean,
    hasAccolades: Boolean,
): CountdownInterior {
    val interiorHeight = circleSize.value * INTERIOR_CONTENT_HEIGHT_FRACTION
    val reserve =
        (RESERVE_HEADER + RESERVE_HEADER_SPACER + RESERVE_NUMERALS + if (showSeconds) RESERVE_SECONDS else 0f) *
            counterScale
    var remaining = interiorHeight - reserve

    fun take(present: Boolean, base: Float): Boolean {
        if (!present) return false
        val height = base * counterScale
        if (height > remaining) return false
        remaining -= height
        return true
    }

    // Priority high → low. The busy sub-line only survives where Net does.
    val showNet = take(hasNet, ROW_NET)
    val showDetours = take(hasDetours, ROW_DETOURS)
    val showFocus = take(hasFocus, ROW_FOCUS)
    val showBusy = take(showNet && hasBusy, ROW_BUSY)
    val showAccolades = take(hasAccolades, ROW_ACCOLADES)

    return CountdownInterior(
        showNet = showNet,
        netCompact = showNet && counterScale <= NET_COMPACT_SCALE_THRESHOLD,
        showBusy = showBusy,
        showFocus = showFocus,
        showDetours = showDetours,
        showAccolades = showAccolades,
    )
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.CountdownInteriorTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Lint**

Run: `./gradlew ktlintCheck`
Expected: BUILD SUCCESSFUL (run `./gradlew ktlintFormat` first if it flags style).

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/CountdownScaling.kt \
        composeApp/src/commonTest/kotlin/fr/dayview/app/CountdownInteriorTest.kt
git commit -m "Add countdown interior priority-budget helper"
```

---

### Task 2: Wire the helper into CountdownCircle

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (center `Column`, ~lines 1077–1196)

**Interfaces:**
- Consumes: `countdownInterior(...)` and `CountdownInterior` from Task 1; existing `counterScale`, `circleSize`, `netTime`, `focusedToday`, `detoursTotal`, `detoursOffWindow`, `cleanSessionsToday`, `streakDays`, `showSeconds`.
- Produces: no new public API.

- [ ] **Step 1: Add test tags**

In `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt`, after the existing `FocusRecap` line, add:

```kotlin
    const val NetRemaining = "netRemaining"
    const val Detours = "detoursTotal"
```

- [ ] **Step 2: Compute the interior decision**

In `DayViewTodayScreen.kt`, inside `CountdownCircle`'s inner content `Column` scope — immediately before the `Column(horizontalAlignment = Alignment.CenterHorizontally) {` at line ~1077 — introduce the decision so it is in scope for the rows. Add:

```kotlin
                    val hasNetRow = netTime != null && netTime.busyRemaining > Duration.ZERO
                    val interior = countdownInterior(
                        circleSize = circleSize,
                        counterScale = counterScale,
                        showSeconds = showSeconds,
                        hasNet = hasNetRow,
                        hasBusy = hasNetRow,
                        hasFocus = focusedToday > Duration.ZERO,
                        hasDetours = detoursTotal > Duration.ZERO,
                        hasAccolades = cleanSessionsToday > 0 || streakDays > 0,
                    )
```

Place it right after the `CompositionLocalProvider(` opening block's lambda brace and before `Column(...)` — i.e. between line 1076 (`) {`) and line 1077 (`Column(...) {`).

- [ ] **Step 3: Replace the Net + busy block**

Replace the existing block (lines ~1102–1117):

```kotlin
                            if (netTime != null && netTime.busyRemaining > Duration.ZERO) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    stringResource(Res.string.net_remaining, formatDurationHm(netTime.netRemaining)),
                                    color = colors.mint,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = .5.sp,
                                )
                                Text(
                                    stringResource(Res.string.busy_remaining, formatDurationHm(netTime.busyRemaining)),
                                    color = colors.muted,
                                    fontSize = 11.sp,
                                    letterSpacing = .5.sp,
                                )
                            }
```

with:

```kotlin
                            if (interior.showNet && netTime != null) {
                                Spacer(Modifier.height(6.dp * counterScale))
                                Text(
                                    if (interior.netCompact) {
                                        formatDurationHm(netTime.netRemaining)
                                    } else {
                                        stringResource(Res.string.net_remaining, formatDurationHm(netTime.netRemaining))
                                    },
                                    color = colors.mint,
                                    fontSize = (14 * counterScale).sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = (.5f * counterScale).sp,
                                    modifier = Modifier.testTag(DayViewTestTags.NetRemaining),
                                )
                                if (interior.showBusy) {
                                    Text(
                                        stringResource(Res.string.busy_remaining, formatDurationHm(netTime.busyRemaining)),
                                        color = colors.muted,
                                        fontSize = (11 * counterScale).sp,
                                        letterSpacing = (.5f * counterScale).sp,
                                    )
                                }
                            }
```

- [ ] **Step 4: Replace the Focus block**

Replace the existing block (lines ~1118–1127):

```kotlin
                            if (focusedToday > Duration.ZERO) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    stringResource(Res.string.focused_today, formatDurationHm(focusedToday)),
                                    color = colors.mint,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = .5.sp,
                                )
                            }
```

with:

```kotlin
                            if (interior.showFocus) {
                                Spacer(Modifier.height(6.dp * counterScale))
                                Text(
                                    stringResource(Res.string.focused_today, formatDurationHm(focusedToday)),
                                    color = colors.mint,
                                    fontSize = (13 * counterScale).sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = (.5f * counterScale).sp,
                                )
                            }
```

- [ ] **Step 5: Replace the Détours block**

Replace the existing block (lines ~1128–1145):

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
                                )
                            }
```

with:

```kotlin
                            if (interior.showDetours) {
                                Spacer(Modifier.height(6.dp * counterScale))
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
                                    fontSize = (13 * counterScale).sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = (.5f * counterScale).sp,
                                    modifier = Modifier.testTag(DayViewTestTags.Detours),
                                )
                            }
```

- [ ] **Step 6: Gate the accolades block**

The clean-sessions/streak block currently opens with `if (cleanSessionsToday > 0 || streakDays > 0) {` (line ~1158). Change that condition to also require the budget flag:

```kotlin
                        if (interior.showAccolades && (cleanSessionsToday > 0 || streakDays > 0)) {
```

Leave the block's body unchanged.

- [ ] **Step 7: Lint and run the full test suite**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, no stderr. (Run `./gradlew ktlintFormat` first if style fails.)

- [ ] **Step 8: Visual verification and constant tuning**

Run: `./gradlew :composeApp:run`
Then resize the desktop window from large to small and confirm:
- Large window: all rows visible, full size.
- Shrinking: pips drop first, then busy sub-line, then Focus, then Détours; Net collapses to `6 h 26` (no "Net" label) before disappearing.
- No row ever overflows the ring stroke or collides with the moment marker / detour bodies.

If a size looks cramped or too sparse, tune only the constants in `CountdownScaling.kt`
(`INTERIOR_CONTENT_HEIGHT_FRACTION`, the `ROW_*`/`RESERVE_*` values, `NET_COMPACT_SCALE_THRESHOLD`) and re-run — do not change the algorithm. Re-run Task 1 tests after tuning; adjust the test expectations only if a deliberate constant change moved a boundary.

- [ ] **Step 9: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/CountdownScaling.kt
git commit -m "Degrade countdown ring interior by priority on small rings"
```

---

## Self-Review

**Spec coverage:**
- Scale rows 2–6 with `counterScale` → Task 2 Steps 3–6 multiply every fixed font size and spacer by `counterScale`. ✓
- Net compact collapse before dropping → `netCompact` flag (Task 1) + label-less render (Task 2 Step 3). ✓
- Priority cull bottom-up against a height budget from ring diameter → `countdownInterior` (Task 1). ✓
- Priority ranking numerals→Net→Détours→Focus→busy→pips → take-order in Task 1 Step 3 and gating in Task 2. ✓
- No change below the ring / no new user setting → none touched. ✓
- Thresholds fall out of the fit, not hard-coded breakpoints → single height-budget loop, only empirical size constants. ✓

**Placeholder scan:** No TBD/TODO; every code step shows full code. ✓

**Type consistency:** `CountdownInterior` fields (`showNet`, `netCompact`, `showBusy`, `showFocus`, `showDetours`, `showAccolades`) and `countdownInterior(...)` parameter names/order are identical across Task 1 definition, Task 1 tests, and Task 2 call site. Test tags `NetRemaining`/`Detours` defined in Task 2 Step 1 before use in Steps 3/5. ✓
