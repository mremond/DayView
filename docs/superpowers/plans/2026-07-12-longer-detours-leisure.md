# Longer Detours (Leisure) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a detour longer than an hour be captured in one gesture and drawn honestly on the ring, so leisure (a series, reading) fits the neutral off-goal spectrum without any new data model.

**Architecture:** Two independent changes to the existing detour feature. Volet B is pure ring-geometry logic in `Detours.kt` (raise the size-saturation cap, switch to a square-root scale). Volet A is a quick-capture UI change in `DetoursUi.kt` (a "Longer" affordance revealing multi-hour pills). The `DetourEpisode` model, persistence, and capture callback are untouched — storage already supports up to 12 h.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, `kotlin.time.Duration`, Compose resources (i18n), Compose UI test (`runComposeUiTest`), kotlin.test.

## Global Constraints

- JDK 21 toolchain; Android compileSdk 36 (Robolectric needs 21).
- ktlint is enforced — run `./gradlew ktlintCheck` (or `ktlintFormat`) before committing.
- Full gate before each commit: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest` — green, no stderr.
- Commit messages in English, describing the change only. **Never** add a "Generated with Claude Code" footer, a `Co-Authored-By: Claude` trailer, any AI/Anthropic reference, or a test-plan/verification section. Do not reference the spec or `docs/superpowers/` in commit messages.
- Desktop UI tests must never assert `stringResource` text (unresolved under `runComposeUiTest` on CI) — drive by test tag only.
- New user-facing strings go in BOTH `composeResources/values/strings.xml` (English) and `composeResources/values-fr/strings.xml` (French).
- `DetourEpisode`, its encoding/persistence, day-scoped lifecycle, per-source coloring, and `hitTestDetourBody` stay unchanged.

---

### Task 1: Scale long detour bodies up to three hours (Volet B)

Raise the body-size saturation from 60 min to 3 h and switch the linear size fraction to a square-root curve, so a long leisure block reads clearly larger than a short interruption without dominating the ring. Pure logic; one source file and its test.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/Detours.kt` (constants at :114-115, size fraction at :136-137, add `sqrt` import at the `kotlin.math.*` import block :7-8)
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DetoursTest.kt` (replace `bodySizeFractionClampsBetween5And60Minutes` at :119-132)

**Interfaces:**
- Consumes: nothing new.
- Produces: `detourBodies(windowStart, windowEnd, episodes)` unchanged signature; `DetourBody.sizeFraction` now saturates at a 3 h duration and follows a square-root scale over `[5 min, 180 min]`.

- [ ] **Step 1: Replace the existing size-fraction test with the new scale**

In `DetoursTest.kt`, replace the whole `bodySizeFractionClampsBetween5And60Minutes` test (lines 119-132) with:

```kotlin
    @Test
    fun bodySizeFractionScalesBySqrtUpToThreeHours() {
        val start = t(0L)
        val end = t(36_000_000L) // 10 h window
        fun sizeOf(minutes: Long): Float = detourBodies(
            start,
            end,
            listOf(DetourEpisode(t(7_200_000L), t(7_200_000L + minutes * 60_000L), "x")),
        ).single().sizeFraction
        assertEquals(0f, sizeOf(5)) // floor: 5 min → 0
        assertEquals(1f, sizeOf(180)) // ceiling: 3 h → 1
        assertEquals(1f, sizeOf(240)) // clamped past the 3 h cap
        // Square-root growth: steep early, gentle late. A 60 min body no longer saturates.
        assertEquals(.5606f, sizeOf(60), absoluteTolerance = .001f) // sqrt((60-5)/175)
        assertEquals(.6969f, sizeOf(90), absoluteTolerance = .001f) // sqrt((90-5)/175)
        assertTrue(sizeOf(30) < sizeOf(60))
        assertTrue(sizeOf(60) < sizeOf(90))
        assertTrue(sizeOf(90) < sizeOf(120))
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.DetoursTest"`
Expected: FAIL — `bodySizeFractionScalesBySqrtUpToThreeHours` fails because the current linear scale saturates at 60 min (`sizeOf(60)` returns `1f`, not `.5606f`).

- [ ] **Step 3: Add the `sqrt` import**

In `Detours.kt`, in the `kotlin.math` import group (after `import kotlin.math.hypot` at line 8), add:

```kotlin
import kotlin.math.sqrt
```

- [ ] **Step 4: Raise the saturation cap to 3 h**

In `Detours.kt`, change line 115:

```kotlin
private val MAX_BODY_DURATION = 60.minutes
```

to:

```kotlin
private val MAX_BODY_DURATION = 180.minutes
```

(`MIN_BODY_DURATION = 5.minutes` at line 114 stays.)

- [ ] **Step 5: Switch the size fraction to a square-root scale**

In `Detours.kt`, replace the size-fraction computation (lines 136-137):

```kotlin
        val sizeFraction = ((episode.duration - MIN_BODY_DURATION) / (MAX_BODY_DURATION - MIN_BODY_DURATION))
            .toFloat().coerceIn(0f, 1f)
```

with:

```kotlin
        val linearFraction = ((episode.duration - MIN_BODY_DURATION) / (MAX_BODY_DURATION - MIN_BODY_DURATION))
            .toFloat().coerceIn(0f, 1f)
        val sizeFraction = sqrt(linearFraction)
```

- [ ] **Step 6: Update the `detourBodies` KDoc to match**

In `Detours.kt`, in the KDoc above `detourBodies` (lines 117-122), replace the phrase describing the size:

```
 * window start` convention as [busyBlockArcs]), size fraction 0..1 from the
 * duration clamped to [5 min, 60 min]. Episodes whose midpoint falls outside the
 * window are dropped.
```

with:

```
 * window start` convention as [busyBlockArcs]), size fraction 0..1 from the
 * duration over [5 min, 3 h] on a square-root scale (steep early, gentle late).
 * Episodes whose midpoint falls outside the window are dropped.
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.DetoursTest"`
Expected: PASS — all `DetoursTest` tests green, including `bodySizeFractionScalesBySqrtUpToThreeHours`.

- [ ] **Step 8: Run the full gate**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, no stderr.

- [ ] **Step 9: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/Detours.kt composeApp/src/commonTest/kotlin/fr/dayview/app/DetoursTest.kt
git commit -m "Scale long detour bodies up to three hours on the ring"
```

---

### Task 2: Quick capture reaches durations longer than an hour (Volet A)

Add a "Longer" affordance to the quick-capture dialog that reveals `1 h 30 · 2 h · 3 h` pills, mirroring the existing "Adjust start" reveal pattern. The short pills (`5·15·30·45·60`) and the fine 5-minute list editor are unchanged.

**Files:**
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml` (add `detour_duration_more` near :215)
- Modify: `composeApp/src/commonMain/composeResources/values-fr/strings.xml` (add `detour_duration_more` near :215)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt` (add `DetourLongToggle` const and `detourDurationChip(minutes)` fun)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DetoursUi.kt` (imports; `DETOUR_LONG_DURATION_CHOICES`; `showLongDurations` state and reveal block inside `DetourCaptureContent`, after the short-duration row at :277)
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/DetourCaptureTest.kt` (add one test)

**Interfaces:**
- Consumes: `DetourChip(label, selected, modifier, textAlign, onLongClick, onLongClickLabel, onClick)` from `DetoursUi.kt`; `formatDurationHm(Duration)` from `CalendarNetTime.kt` (`90.minutes` → `"1 h 30"`, `120` → `"2 h 00"`, `180` → `"3 h 00"`); `DetourCaptureContent(recentMotifs, now, onConfirm, onForget, onDismiss)` with `onConfirm: (motif: String, durationMinutes: Int, startMinutesOfDay: Int?)`.
- Produces: `DayViewTestTags.DetourLongToggle` (String) and `DayViewTestTags.detourDurationChip(minutes: Int)` (String) for the new pills; `onConfirm` now emits `durationMinutes` up to 180.

- [ ] **Step 1: Write the failing desktop UI test**

In `DetourCaptureTest.kt`, add this test inside the `DetourCaptureTest` class (after `adjustingPinsAnExplicitStart`, before the closing brace):

```kotlin
    @Test
    fun longerRevealsAndSelectsMultiHourDurations() = runComposeUiTest {
        var captured: Triple<String, Int, Int?>? = null
        setContent {
            DetourCaptureContent(
                recentMotifs = emptyList(),
                now = midWindowNow(),
                onConfirm = { motif, duration, start -> captured = Triple(motif, duration, start) },
                onForget = {},
                onDismiss = {},
            )
        }
        onNodeWithTag(DayViewTestTags.DetourMotifField).performTextInput("série")
        onNodeWithTag(DayViewTestTags.DetourLongToggle).performClick()
        onNodeWithTag(DayViewTestTags.detourDurationChip(180)).performClick()
        onNodeWithTag(DayViewTestTags.DetourConfirm).performClick()

        val (motif, duration, start) = captured!!
        assertEquals("série", motif)
        assertEquals(180, duration) // 3 h reached from quick capture
        assertNull(start) // start untouched → "ends now"
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DetourCaptureTest"`
Expected: FAIL — compilation error / unresolved `DayViewTestTags.DetourLongToggle` and `DayViewTestTags.detourDurationChip`.

- [ ] **Step 3: Add the test tags**

In `DayViewTestTags.kt`, add a constant next to the other `Detour*` tags (after `DetourConfirm` at line 21):

```kotlin
    const val DetourLongToggle = "detourLongToggle"
```

and add this function next to `settingsCategoryRow` (after line 35, inside the object):

```kotlin
    fun detourDurationChip(minutes: Int): String = "detourDuration$minutes"
```

- [ ] **Step 4: Add the string resource (English)**

In `composeResources/values/strings.xml`, after the `detour_duration_section` line (line 215), add:

```xml
    <string name="detour_duration_more">Longer</string>
```

- [ ] **Step 5: Add the string resource (French)**

In `composeResources/values-fr/strings.xml`, after the `detour_duration_section` line (line 214), add:

```xml
    <string name="detour_duration_more">Plus long</string>
```

- [ ] **Step 6: Add imports and the long-duration choices in `DetoursUi.kt`**

In `DetoursUi.kt`, add the resource import in the `generated.resources` import group (alphabetically near the other `detour_duration_*` imports, after `detour_duration_label`):

```kotlin
import fr.dayview.app.generated.resources.detour_duration_more
```

Add the duration import next to the existing `kotlin.time.Instant` import (line 81):

```kotlin
import kotlin.time.Duration.Companion.minutes
```

Add the long-duration constant next to `DETOUR_DURATION_CHOICES` (after line 186):

```kotlin
private val DETOUR_LONG_DURATION_CHOICES = listOf(90, 120, 180)
```

- [ ] **Step 7: Add the reveal state**

In `DetourCaptureContent`, next to the other `remember` state (after `var durationMinutes by remember { mutableIntStateOf(15) }` at line 219), add:

```kotlin
    var showLongDurations by remember { mutableStateOf(false) }
```

- [ ] **Step 8: Insert the "Longer" reveal block after the short-duration row**

In `DetourCaptureContent`, immediately after the short-duration `Row { … }` closes (line 277) and before the existing `Spacer(Modifier.height(14.dp))` (line 278), insert:

```kotlin
        Spacer(Modifier.height(8.dp))
        if (!showLongDurations) {
            Text(
                stringResource(Res.string.detour_duration_more),
                color = colors.amber,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { showLongDurations = true }
                    .testTag(DayViewTestTags.DetourLongToggle)
                    .padding(vertical = 6.dp, horizontal = 6.dp),
            )
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                DETOUR_LONG_DURATION_CHOICES.forEach { minutes ->
                    DetourChip(
                        formatDurationHm(minutes.minutes),
                        selected = minutes == durationMinutes,
                        modifier = Modifier.weight(1f).testTag(DayViewTestTags.detourDurationChip(minutes)),
                        textAlign = TextAlign.Center,
                    ) { durationMinutes = minutes }
                }
            }
        }
```

- [ ] **Step 9: Run the test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DetourCaptureTest"`
Expected: PASS — `longerRevealsAndSelectsMultiHourDurations` and the two existing tests green.

- [ ] **Step 10: Run the full gate**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, no stderr. (If ktlint reports import ordering, run `./gradlew ktlintFormat` and re-run the gate.)

- [ ] **Step 11: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DetoursUi.kt composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt composeApp/src/commonMain/composeResources/values/strings.xml composeApp/src/commonMain/composeResources/values-fr/strings.xml composeApp/src/desktopTest/kotlin/fr/dayview/app/DetourCaptureTest.kt
git commit -m "Let quick capture declare detours longer than an hour"
```

---

## Verification (whole feature)

- [ ] Run the full gate one final time: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest` — green, no stderr.
- [ ] Manual smoke (desktop): `./gradlew :composeApp:run`, open the detour quick-capture, tap **Longer**, pick **3 h**, confirm; the body on the ring is visibly larger than a short detour. (Note the documented boundaries: a long quick-capture whose start would fall before the day-window start is clamped to it; a detour whose midpoint is outside the day window is stored/listed but not drawn.)
