# Detour Start Manual Input and 5-Minute Snap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user type a detour's start time (and duration in the edit form) directly, and make the − / + steppers land on multiples of 5 minutes.

**Architecture:** Two pure functions in a new common file (`snapToFive`, `parseMinutesOfDay`/`parseDurationMinutes`) carry all the logic and are unit-tested in commonTest. A small shared composable `EditableTimeValue` in `DetoursUi.kt` turns the displayed value into a text field on tap; both detour forms use it. UI behavior is covered by desktop Compose tests driven through test tags.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, kotlinx-datetime, kotlin.test, `runComposeUiTest` (desktopTest).

**Spec:** `docs/superpowers/specs/2026-07-13-detour-start-manual-input-design.md`

## Global Constraints

- Run before every commit: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest` — all green, no stderr.
- Compose UI tests must NEVER assert on `stringResource` text (unresolved under `runComposeUiTest` on CI); use test tags and seeded data only.
- Commit messages in English, plain descriptive sentences (repo style), no AI references, no test-plan sections.
- String resources: every user-visible string goes to BOTH `values/strings.xml` (English) and `values-fr/strings.xml`. Use a single `%` for literals; formatting placeholders follow the existing `%1$s` style.
- Start stepper snap results clamp to `0 .. 23*60+55`; duration stepper snap results clamp to `5 .. 720`. Manual start entry may reach `23:59`.

---

### Task 1: `snapToFive` pure function

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/TimeOfDayInput.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/TimeOfDayInputTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `fun snapToFive(current: Int, direction: Int): Int` — `direction` is `+1` or `-1`; from a non-multiple of 5 moves to the nearest multiple of 5 in that direction, from a multiple of 5 steps a full ±5. No clamping (call sites clamp).

- [ ] **Step 1: Write the failing tests**

Create `composeApp/src/commonTest/kotlin/fr/dayview/app/TimeOfDayInputTest.kt`:

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class TimeOfDayInputTest {
    @Test
    fun snapMovesMisalignedValueToNearestMultipleInPressedDirection() {
        // 14:32 → "+" lands on 14:35, "−" lands on 14:30.
        assertEquals(14 * 60 + 35, snapToFive(14 * 60 + 32, +1))
        assertEquals(14 * 60 + 30, snapToFive(14 * 60 + 32, -1))
        // One minute off either side of a multiple.
        assertEquals(875, snapToFive(871, +1))
        assertEquals(870, snapToFive(874, -1))
    }

    @Test
    fun snapStepsAlignedValueByFive() {
        assertEquals(14 * 60 + 35, snapToFive(14 * 60 + 30, +1))
        assertEquals(14 * 60 + 25, snapToFive(14 * 60 + 30, -1))
        assertEquals(5, snapToFive(0, +1))
        assertEquals(-5, snapToFive(0, -1)) // call sites clamp
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.TimeOfDayInputTest"`
Expected: FAIL to compile — `snapToFive` unresolved.

- [ ] **Step 3: Write the minimal implementation**

Create `composeApp/src/commonMain/kotlin/fr/dayview/app/TimeOfDayInput.kt`:

```kotlin
package fr.dayview.app

/**
 * Directional stepper snap: from a value that is not a multiple of 5, move to the
 * nearest multiple of 5 in [direction] (+1 / −1); from an aligned value, step ±5.
 * Callers clamp the result to their own range.
 */
fun snapToFive(current: Int, direction: Int): Int = when {
    direction > 0 -> (current.floorDiv(5) + 1) * 5
    current % 5 == 0 -> current - 5
    else -> current.floorDiv(5) * 5
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.TimeOfDayInputTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/TimeOfDayInput.kt composeApp/src/commonTest/kotlin/fr/dayview/app/TimeOfDayInputTest.kt
git commit -m "Add directional 5-minute snap helper for detour steppers"
```

---

### Task 2: Tolerant parsing — `parseMinutesOfDay` and `parseDurationMinutes`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/TimeOfDayInput.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/TimeOfDayInputTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `fun parseMinutesOfDay(text: String, use24Hour: Boolean): Int?` — wall-clock text → minutes of day in `0..1439`, or `null` when invalid. Accepts `14:32`, `14h32`, `14h`, `1432`, `932`, `14`, `9`; in 12-hour mode also an optional case-insensitive `a`/`am`/`p`/`pm` suffix (with or without a space). Without a suffix the text is read as 24-hour.
  - `fun parseDurationMinutes(text: String): Int?` — duration text → minutes in `5..720`, or `null`. Accepts a plain minute count (`45`), `1h30`, `1:30`, `2h`.

- [ ] **Step 1: Write the failing tests**

Append to `TimeOfDayInputTest.kt`:

```kotlin
    @Test
    fun parsesColonAndFrenchHourSeparators() {
        assertEquals(14 * 60 + 32, parseMinutesOfDay("14:32", use24Hour = true))
        assertEquals(9 * 60 + 5, parseMinutesOfDay("9:05", use24Hour = true))
        assertEquals(14 * 60 + 32, parseMinutesOfDay("14h32", use24Hour = true))
        assertEquals(14 * 60, parseMinutesOfDay("14h", use24Hour = true))
        assertEquals(14 * 60 + 32, parseMinutesOfDay("  14:32  ", use24Hour = true))
    }

    @Test
    fun parsesBareDigits() {
        assertEquals(14 * 60 + 32, parseMinutesOfDay("1432", use24Hour = true))
        assertEquals(9 * 60 + 32, parseMinutesOfDay("932", use24Hour = true))
        assertEquals(14 * 60, parseMinutesOfDay("14", use24Hour = true))
        assertEquals(9 * 60, parseMinutesOfDay("9", use24Hour = true))
        assertEquals(0, parseMinutesOfDay("0", use24Hour = true))
        assertEquals(23 * 60 + 59, parseMinutesOfDay("2359", use24Hour = true))
    }

    @Test
    fun parsesTwelveHourSuffixesOnlyInTwelveHourMode() {
        assertEquals(14 * 60 + 30, parseMinutesOfDay("2:30 pm", use24Hour = false))
        assertEquals(14 * 60 + 30, parseMinutesOfDay("2:30PM", use24Hour = false))
        assertEquals(0, parseMinutesOfDay("12am", use24Hour = false))
        assertEquals(12 * 60, parseMinutesOfDay("12pm", use24Hour = false))
        assertEquals(9 * 60 + 15, parseMinutesOfDay("9:15a", use24Hour = false))
        // Without a suffix, 12h mode still reads the text as 24-hour.
        assertEquals(14 * 60 + 30, parseMinutesOfDay("14:30", use24Hour = false))
        // Suffixes are rejected in 24-hour mode.
        assertNull(parseMinutesOfDay("2:30 pm", use24Hour = true))
    }

    @Test
    fun rejectsInvalidWallClockText() {
        assertNull(parseMinutesOfDay("", use24Hour = true))
        assertNull(parseMinutesOfDay("24:00", use24Hour = true))
        assertNull(parseMinutesOfDay("9:75", use24Hour = true))
        assertNull(parseMinutesOfDay("9:5", use24Hour = true))
        assertNull(parseMinutesOfDay("abc", use24Hour = true))
        assertNull(parseMinutesOfDay("12345", use24Hour = true))
        assertNull(parseMinutesOfDay("0am", use24Hour = false))
        assertNull(parseMinutesOfDay("13pm", use24Hour = false))
    }

    @Test
    fun parsesDurationsWithinRange() {
        assertEquals(45, parseDurationMinutes("45"))
        assertEquals(5, parseDurationMinutes("5"))
        assertEquals(90, parseDurationMinutes("1h30"))
        assertEquals(90, parseDurationMinutes("1:30"))
        assertEquals(120, parseDurationMinutes("2h"))
        assertEquals(720, parseDurationMinutes("720"))
    }

    @Test
    fun rejectsInvalidDurations() {
        assertNull(parseDurationMinutes(""))
        assertNull(parseDurationMinutes("4")) // below the 5-minute floor
        assertNull(parseDurationMinutes("721"))
        assertNull(parseDurationMinutes("13h")) // 780 min > 720
        assertNull(parseDurationMinutes("1h75"))
        assertNull(parseDurationMinutes("abc"))
    }
```

Add `import kotlin.test.assertNull` to the test file's imports.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.TimeOfDayInputTest"`
Expected: FAIL to compile — `parseMinutesOfDay` / `parseDurationMinutes` unresolved.

- [ ] **Step 3: Write the implementation**

Append to `TimeOfDayInput.kt`:

```kotlin
private val HOUR_MINUTE = Regex("""^(\d{1,2})[:h](\d{2})?$""")
private val BARE_DIGITS = Regex("""^\d{1,4}$""")
private val MERIDIEM_SUFFIX = Regex("""^(.*?)\s*([ap])m?$""")

/**
 * Tolerant wall-clock parsing for the detour time fields. Accepts "14:32",
 * "14h32", "14h", "1432", "932", "14"; in 12-hour mode an optional am/pm
 * suffix ("2:30 pm", "12am"). Returns minutes of day, or null when invalid.
 */
fun parseMinutesOfDay(text: String, use24Hour: Boolean): Int? {
    var body = text.trim().lowercase()
    var meridiem: String? = null
    if (!use24Hour) {
        MERIDIEM_SUFFIX.matchEntire(body)?.let { match ->
            body = match.groupValues[1].trim()
            meridiem = match.groupValues[2]
        }
    }
    val (hourText, minuteText) = splitHourMinute(body) ?: return null
    val hour = hourText.toIntOrNull() ?: return null
    val minute = (if (minuteText.isEmpty()) "0" else minuteText).toIntOrNull() ?: return null
    if (minute > 59) return null
    val resolvedHour = when (meridiem) {
        null -> hour.takeIf { it <= 23 }
        "a" -> hour.takeIf { it in 1..12 }?.mod(12)
        else -> hour.takeIf { it in 1..12 }?.mod(12)?.plus(12)
    } ?: return null
    return resolvedHour * 60 + minute
}

/** "45", "1h30", "1:30", "2h" → whole minutes in 5..720, or null when invalid. */
fun parseDurationMinutes(text: String): Int? {
    val body = text.trim().lowercase()
    if (BARE_DIGITS.matches(body)) return body.toIntOrNull()?.takeIf { it in 5..720 }
    val match = HOUR_MINUTE.matchEntire(body) ?: return null
    val minutes = (if (match.groupValues[2].isEmpty()) "0" else match.groupValues[2]).toInt()
    if (minutes > 59) return null
    return (match.groupValues[1].toInt() * 60 + minutes).takeIf { it in 5..720 }
}

/** Splits "14:32" / "14h32" / "14h" / "1432" / "14" into hour and minute texts. */
private fun splitHourMinute(body: String): Pair<String, String>? {
    HOUR_MINUTE.matchEntire(body)?.let { return it.groupValues[1] to it.groupValues[2] }
    if (!BARE_DIGITS.matches(body)) return null
    return when {
        body.length <= 2 -> body to ""
        else -> body.dropLast(2) to body.takeLast(2)
    }
}
```

Note: `BARE_DIGITS` caps bare digits at 4, so `"12345"` is rejected; the split of a 3–4 digit run takes the last two digits as minutes.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.TimeOfDayInputTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/TimeOfDayInput.kt composeApp/src/commonTest/kotlin/fr/dayview/app/TimeOfDayInputTest.kt
git commit -m "Add tolerant wall-clock and duration parsing for detour fields"
```

---

### Task 3: `EditableTimeValue` composable + quick-capture wiring

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DetoursUi.kt` (new composable; rewire the start stepper in `DetourCaptureContent`, lines ~326-352)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Modify: `composeApp/src/commonMain/composeResources/values-fr/strings.xml`
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/DetourCaptureTest.kt`

**Interfaces:**
- Consumes: `snapToFive`, `parseMinutesOfDay` (Tasks 1–2); existing `TimeButton`, `formatMinutesOfDay`, `LocalUses24HourClock`, `LocalDayViewColors`.
- Produces: `@Composable internal fun EditableTimeValue(displayText: String, editText: String, parse: (String) -> Int?, editLabel: String, valueTag: String, fieldTag: String, onCommit: (Int) -> Unit)` — Task 4 reuses it verbatim. New tags `DetourStartValue`, `DetourStartField`, `DetourStartDecrease`.

- [ ] **Step 1: Write the failing UI tests**

Append to `DetourCaptureTest.kt`:

```kotlin
    @Test
    fun typingAStartTimePinsIt() = runComposeUiTest {
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
        onNodeWithTag(DayViewTestTags.DetourMotifField).performTextInput("café")
        onNodeWithTag(DayViewTestTags.DetourStartAdjust).performClick()
        onNodeWithTag(DayViewTestTags.DetourStartValue).performClick()
        onNodeWithTag(DayViewTestTags.DetourStartField).performTextClearance()
        onNodeWithTag(DayViewTestTags.DetourStartField).performTextInput("9h05")
        onNodeWithTag(DayViewTestTags.DetourStartField).performImeAction()
        onNodeWithTag(DayViewTestTags.DetourConfirm).performClick()

        assertEquals(9 * 60 + 5, captured!!.third)
    }

    @Test
    fun invalidTypedStartRevertsToPreviousValue() = runComposeUiTest {
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
        onNodeWithTag(DayViewTestTags.DetourMotifField).performTextInput("café")
        onNodeWithTag(DayViewTestTags.DetourStartAdjust).performClick()
        onNodeWithTag(DayViewTestTags.DetourStartValue).performClick()
        onNodeWithTag(DayViewTestTags.DetourStartField).performTextClearance()
        onNodeWithTag(DayViewTestTags.DetourStartField).performTextInput("99:99")
        onNodeWithTag(DayViewTestTags.DetourStartField).performImeAction()
        // The field closes without committing; the start stays unpinned.
        onNodeWithTag(DayViewTestTags.DetourStartValue).assertExists()
        onNodeWithTag(DayViewTestTags.DetourConfirm).performClick()

        assertNull(captured!!.third)
    }

    @Test
    fun nudgingFromTypedMisalignedStartSnapsToMultipleOfFive() = runComposeUiTest {
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
        onNodeWithTag(DayViewTestTags.DetourMotifField).performTextInput("café")
        onNodeWithTag(DayViewTestTags.DetourStartAdjust).performClick()
        onNodeWithTag(DayViewTestTags.DetourStartValue).performClick()
        onNodeWithTag(DayViewTestTags.DetourStartField).performTextClearance()
        onNodeWithTag(DayViewTestTags.DetourStartField).performTextInput("9h07")
        onNodeWithTag(DayViewTestTags.DetourStartField).performImeAction()
        onNodeWithTag(DayViewTestTags.DetourStartIncrease).performClick()
        onNodeWithTag(DayViewTestTags.DetourConfirm).performClick()

        // 9:07 + snaps up to 9:10, not 9:12.
        assertEquals(9 * 60 + 10, captured!!.third)
    }
```

Add imports `androidx.compose.ui.test.performImeAction` and `androidx.compose.ui.test.performTextClearance` to the test file.

(If `performImeAction()` proves flaky on the desktop harness, commit by moving focus instead: `onNodeWithTag(DayViewTestTags.DetourMotifField).performClick()` — focus loss also commits.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DetourCaptureTest"`
Expected: FAIL to compile — `DetourStartValue` / `DetourStartField` unresolved.

- [ ] **Step 3: Add test tags and strings**

In `DayViewTestTags.kt`, after `DetourStartIncrease`:

```kotlin
    const val DetourStartDecrease = "detourStartDecrease"
    const val DetourStartValue = "detourStartValue"
    const val DetourStartField = "detourStartField"
```

In `values/strings.xml`, next to the other `detour_start_*` strings:

```xml
    <string name="detour_start_edit_label">Type the start time</string>
```

In `values-fr/strings.xml`:

```xml
    <string name="detour_start_edit_label">Saisir l\'heure de début</string>
```

(Match the file's existing apostrophe escaping: if other French strings use a plain `'`, use a plain `'`.)

- [ ] **Step 4: Add the `EditableTimeValue` composable**

At the end of `DetoursUi.kt`, before `formatMinutesOfDay`:

```kotlin
/**
 * A stepper's value text that turns into a small text field on tap. Enter or
 * focus loss commits when [parse] accepts the draft; otherwise the previous
 * value is kept. While editing, an unparsable draft shows the error border.
 */
@Composable
internal fun EditableTimeValue(
    displayText: String,
    editText: String,
    parse: (String) -> Int?,
    editLabel: String,
    valueTag: String,
    fieldTag: String,
    onCommit: (Int) -> Unit,
) {
    val colors = LocalDayViewColors.current
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    if (!editing) {
        Text(
            displayText,
            color = colors.cloud,
            fontSize = 17.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClickLabel = editLabel) {
                    draft = TextFieldValue(editText, selection = TextRange(0, editText.length))
                    editing = true
                }
                .testTag(valueTag)
                .padding(horizontal = 4.dp, vertical = 4.dp),
        )
    } else {
        val focusRequester = remember { FocusRequester() }
        var wasFocused by remember { mutableStateOf(false) }
        val isError = parse(draft.text) == null
        fun commit() {
            if (!editing) return
            parse(draft.text)?.let(onCommit)
            editing = false
        }
        BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            textStyle = TextStyle(
                color = colors.cloud,
                fontSize = 17.sp,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.Center,
            ),
            cursorBrush = Brush.verticalGradient(listOf(colors.mint, colors.mint)),
            modifier = Modifier
                .width(96.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { focusState ->
                    if (wasFocused && !focusState.isFocused) commit()
                    wasFocused = focusState.isFocused
                }
                .semantics { contentDescription = editLabel }
                .background(colors.overlay.copy(alpha = .045f), RoundedCornerShape(8.dp))
                .border(
                    width = 1.dp,
                    color = if (isError) colors.red.copy(alpha = .8f) else colors.overlay.copy(alpha = .07f),
                    shape = RoundedCornerShape(8.dp),
                )
                .testTag(fieldTag)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
}
```

New imports for `DetoursUi.kt` (keep the file's alphabetical order):

```kotlin
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import fr.dayview.app.generated.resources.detour_start_edit_label
```

- [ ] **Step 5: Rewire the capture dialog's start stepper**

In `DetourCaptureContent`, replace the whole `Row(verticalAlignment = Alignment.CenterVertically) { ... }` under the `detour_start_section` label (the block with the two `TimeButton`s and the `Text` between them) with:

```kotlin
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeButton(
                    label = "−",
                    enabled = startMinutes > 0,
                    onClickLabel = stringResource(Res.string.detour_start_decrease),
                    valueDescription = stringResource(Res.string.detour_start_value, formatMinutesOfDay(startMinutes, uses24Hour)),
                    modifier = Modifier.testTag(DayViewTestTags.DetourStartDecrease),
                ) {
                    pinnedStartMinutes = snapToFive(startMinutes, -1).coerceIn(0, 23 * 60 + 55)
                    startPinned = true
                }
                Spacer(Modifier.width(10.dp))
                EditableTimeValue(
                    displayText = formatMinutesOfDay(startMinutes, uses24Hour),
                    editText = formatMinutesOfDay(startMinutes, uses24Hour),
                    parse = { parseMinutesOfDay(it, uses24Hour) },
                    editLabel = stringResource(Res.string.detour_start_edit_label),
                    valueTag = DayViewTestTags.DetourStartValue,
                    fieldTag = DayViewTestTags.DetourStartField,
                    onCommit = { typed ->
                        pinnedStartMinutes = typed
                        startPinned = true
                    },
                )
                Spacer(Modifier.width(10.dp))
                TimeButton(
                    label = "+",
                    enabled = startMinutes < 23 * 60 + 55,
                    onClickLabel = stringResource(Res.string.detour_start_increase),
                    valueDescription = stringResource(Res.string.detour_start_value, formatMinutesOfDay(startMinutes, uses24Hour)),
                    modifier = Modifier.testTag(DayViewTestTags.DetourStartIncrease),
                ) {
                    pinnedStartMinutes = snapToFive(startMinutes, +1).coerceIn(0, 23 * 60 + 55)
                    startPinned = true
                }
            }
```

- [ ] **Step 6: Run the capture tests**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DetourCaptureTest"`
Expected: PASS (6 tests — the 3 existing ones must stay green; the capture default 12:45 is already a multiple of 5, so `adjustingPinsAnExplicitStart` still lands on 12:50).

- [ ] **Step 7: Run the full gate and commit**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, no stderr.

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DetoursUi.kt composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt composeApp/src/commonMain/composeResources/values/strings.xml composeApp/src/commonMain/composeResources/values-fr/strings.xml composeApp/src/desktopTest/kotlin/fr/dayview/app/DetourCaptureTest.kt
git commit -m "Let the detour capture start time be typed and snap nudges to 5 minutes"
```

---

### Task 4: Edit form wiring — start and duration

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DetoursUi.kt` (`DetourEditForm`, lines ~536-629)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Modify: `composeApp/src/commonMain/composeResources/values-fr/strings.xml`
- Create: `composeApp/src/desktopTest/kotlin/fr/dayview/app/DetourEditFormTest.kt`

**Interfaces:**
- Consumes: `EditableTimeValue` (Task 3), `snapToFive`, `parseMinutesOfDay`, `parseDurationMinutes` (Tasks 1–2), `detourEpisodeAt`, `midWindowNow()` (desktopTest helper, 13:00 local).
- Produces: `DetourEditForm` becomes `internal` (was `private`) so desktopTest can render it directly — it is a plain composable, not wrapped in a `Dialog`, which `runComposeUiTest` cannot reach.

- [ ] **Step 1: Write the failing UI tests**

Create `composeApp/src/desktopTest/kotlin/fr/dayview/app/DetourEditFormTest.kt`:

```kotlin
package fr.dayview.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DetourEditFormTest {
    private fun localMinutes(episode: DetourEpisode): Int {
        val local = episode.start.toLocalDateTime(TimeZone.currentSystemDefault())
        return local.hour * 60 + local.minute
    }

    @Test
    fun nudgesSnapMisalignedStartAndDurationToMultiplesOfFive() = runComposeUiTest {
        var saved: DetourEpisode? = null
        setContent {
            DetourEditForm(
                initial = detourEpisodeAt(midWindowNow(), 14 * 60 + 32, 17, "vélo"),
                now = midWindowNow(),
                onDelete = null,
                onCancel = {},
                onSave = { saved = it },
            )
        }
        onNodeWithTag(DayViewTestTags.DetourEditStartIncrease).performClick()
        onNodeWithTag(DayViewTestTags.DetourEditDurationIncrease).performClick()
        onNodeWithTag(DayViewTestTags.DetourEditSave).performClick()

        // 14:32 + snaps to 14:35; 17 min + snaps to 20 min.
        assertEquals(14 * 60 + 35, localMinutes(saved!!))
        assertEquals(20, saved!!.duration.inWholeMinutes.toInt())
    }

    @Test
    fun typedStartAndDurationAreSaved() = runComposeUiTest {
        var saved: DetourEpisode? = null
        setContent {
            DetourEditForm(
                initial = detourEpisodeAt(midWindowNow(), 14 * 60 + 32, 17, "vélo"),
                now = midWindowNow(),
                onDelete = null,
                onCancel = {},
                onSave = { saved = it },
            )
        }
        onNodeWithTag(DayViewTestTags.DetourEditStartValue).performClick()
        onNodeWithTag(DayViewTestTags.DetourEditStartField).performTextClearance()
        onNodeWithTag(DayViewTestTags.DetourEditStartField).performTextInput("9h05")
        onNodeWithTag(DayViewTestTags.DetourEditStartField).performImeAction()
        onNodeWithTag(DayViewTestTags.DetourEditDurationValue).performClick()
        onNodeWithTag(DayViewTestTags.DetourEditDurationField).performTextClearance()
        onNodeWithTag(DayViewTestTags.DetourEditDurationField).performTextInput("45")
        onNodeWithTag(DayViewTestTags.DetourEditDurationField).performImeAction()
        onNodeWithTag(DayViewTestTags.DetourEditSave).performClick()

        assertEquals(9 * 60 + 5, localMinutes(saved!!))
        assertEquals(45, saved!!.duration.inWholeMinutes.toInt())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DetourEditFormTest"`
Expected: FAIL to compile — `DetourEditForm` is private, tags unresolved.

- [ ] **Step 3: Add test tags and the duration edit string**

In `DayViewTestTags.kt`, after the `DetourStartField` line added in Task 3:

```kotlin
    const val DetourEditStartValue = "detourEditStartValue"
    const val DetourEditStartField = "detourEditStartField"
    const val DetourEditStartIncrease = "detourEditStartIncrease"
    const val DetourEditDurationValue = "detourEditDurationValue"
    const val DetourEditDurationField = "detourEditDurationField"
    const val DetourEditDurationIncrease = "detourEditDurationIncrease"
    const val DetourEditSave = "detourEditSave"
```

In `values/strings.xml`, next to `detour_start_edit_label`:

```xml
    <string name="detour_duration_edit_label">Type the duration in minutes</string>
```

In `values-fr/strings.xml`:

```xml
    <string name="detour_duration_edit_label">Saisir la durée en minutes</string>
```

- [ ] **Step 4: Rewire `DetourEditForm`**

Change the declaration from `private fun DetourEditForm(` to `internal fun DetourEditForm(`.

Replace the start stepper `Row` (inside the first weighted `Column`) with:

```kotlin
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeButton(
                    label = "−",
                    enabled = startMinutes > 0,
                    onClickLabel = stringResource(Res.string.detour_start_decrease),
                    valueDescription = stringResource(Res.string.detour_start_value, formatMinutesOfDay(startMinutes, uses24Hour)),
                ) { startMinutes = snapToFive(startMinutes, -1).coerceIn(0, 23 * 60 + 55) }
                Spacer(Modifier.width(10.dp))
                EditableTimeValue(
                    displayText = formatMinutesOfDay(startMinutes, uses24Hour),
                    editText = formatMinutesOfDay(startMinutes, uses24Hour),
                    parse = { parseMinutesOfDay(it, uses24Hour) },
                    editLabel = stringResource(Res.string.detour_start_edit_label),
                    valueTag = DayViewTestTags.DetourEditStartValue,
                    fieldTag = DayViewTestTags.DetourEditStartField,
                    onCommit = { startMinutes = it },
                )
                Spacer(Modifier.width(10.dp))
                TimeButton(
                    label = "+",
                    enabled = startMinutes < 23 * 60 + 55,
                    onClickLabel = stringResource(Res.string.detour_start_increase),
                    valueDescription = stringResource(Res.string.detour_start_value, formatMinutesOfDay(startMinutes, uses24Hour)),
                    modifier = Modifier.testTag(DayViewTestTags.DetourEditStartIncrease),
                ) { startMinutes = snapToFive(startMinutes, +1).coerceIn(0, 23 * 60 + 55) }
            }
```

Replace the duration stepper `Row` (inside the second weighted `Column`) with:

```kotlin
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeButton(
                    label = "−",
                    enabled = durationMinutes > 5,
                    onClickLabel = stringResource(Res.string.detour_duration_decrease),
                    valueDescription = stringResource(Res.string.detour_duration_value, durationMinutes.toString()),
                ) { durationMinutes = snapToFive(durationMinutes, -1).coerceIn(5, 12 * 60) }
                Spacer(Modifier.width(10.dp))
                EditableTimeValue(
                    displayText = stringResource(Res.string.detour_minutes_chip, durationMinutes.toString()),
                    editText = durationMinutes.toString(),
                    parse = ::parseDurationMinutes,
                    editLabel = stringResource(Res.string.detour_duration_edit_label),
                    valueTag = DayViewTestTags.DetourEditDurationValue,
                    fieldTag = DayViewTestTags.DetourEditDurationField,
                    onCommit = { durationMinutes = it },
                )
                Spacer(Modifier.width(10.dp))
                TimeButton(
                    label = "+",
                    enabled = durationMinutes < 12 * 60,
                    onClickLabel = stringResource(Res.string.detour_duration_increase),
                    valueDescription = stringResource(Res.string.detour_duration_value, durationMinutes.toString()),
                    modifier = Modifier.testTag(DayViewTestTags.DetourEditDurationIncrease),
                ) { durationMinutes = snapToFive(durationMinutes, +1).coerceIn(5, 12 * 60) }
            }
```

Tag the save button by changing the last `FocusActionButton` in `DetourEditForm`:

```kotlin
        FocusActionButton(
            stringResource(Res.string.detour_save_button),
            colors.amber,
            modifier = Modifier.weight(1f).testTag(DayViewTestTags.DetourEditSave),
            enabled = motif.isNotBlank(),
            filled = true,
            onClick = { onSave(detourEpisodeAt(now, startMinutes, durationMinutes, motif)) },
        )
```

Add the new resource import to `DetoursUi.kt`:

```kotlin
import fr.dayview.app.generated.resources.detour_duration_edit_label
```

- [ ] **Step 5: Run the new tests**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DetourEditFormTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Run the full gate and commit**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, no stderr.

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DetoursUi.kt composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt composeApp/src/commonMain/composeResources/values/strings.xml composeApp/src/commonMain/composeResources/values-fr/strings.xml composeApp/src/desktopTest/kotlin/fr/dayview/app/DetourEditFormTest.kt
git commit -m "Let the detour edit form start and duration be typed and snap to 5 minutes"
```
