# Compose UI Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a first suite of Compose Multiplatform UI tests covering the Settings, Today, and Focus flows, running on the desktop/JVM target.

**Architecture:** Tests live in `composeApp/src/desktopTest`. They drive the pure screen composables (`SettingsScreen`, `DayViewScreen`) — never `DayViewApp` (real clock + infinite ticker). Deterministic `DayViewUiState` is built through `DayViewController` from a seeded `DayPreferencesSnapshot` + a timezone-safe fixed `now`. Assertions use recording callback fakes (Settings) and real controller-state transitions (Focus). A small shared `DayViewTestTags` object (commonMain) anchors interaction/assertion queries; rendering assertions use stable text and existing accessibility labels.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform 1.11.1, `compose.desktop.uiTestJUnit4` (`runComposeUiTest`), kotlin-test, kotlinx-datetime, kotlinx-coroutines.

## Global Constraints

- JDK 21 toolchain (`jvmToolchain(21)`); Android compileSdk 36.
- ktlint enforced — every task ends ktlint-clean (`./gradlew ktlintCheck`, or `ktlintFormat` to auto-fix). Use explicit, alphabetically-ordered imports (no wildcards).
- The Robolectric `:composeApp:testDebugUnitTest` run MUST stay green — do not add UI-test deps to `commonTest` or `androidUnitTest`.
- New UI tests run on `:composeApp:desktopTest`.
- Commit messages in English, describe the change only. **Never** add a "Generated with Claude Code" footer, `Co-Authored-By: Claude`, or any reference to Claude/Anthropic/AI. Do not reference `docs/superpowers/` planning docs in commits.
- All new production/test code is package `fr.dayview.app` (same-package types need no imports).

## File Structure

- Create `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt` — shared UI-test tag constants (referenced by production composables and desktop tests to avoid stringly-typed drift).
- Modify `composeApp/build.gradle.kts` — add `desktopTest` source-set dependency.
- Modify `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewSettingsScreen.kt` — add `testTag` to the show-seconds toggle Row and the back link.
- Modify `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` — add `testTag` to the countdown root, the focus-start button, the active focus-stop button.
- Create `composeApp/src/desktopTest/kotlin/fr/dayview/app/ComposeUiTestSmokeTest.kt` — harness smoke test.
- Create `composeApp/src/desktopTest/kotlin/fr/dayview/app/UiTestSupport.kt` — shared helpers (grows across tasks): `midWindowNow`, `seededController`, action-bundle factories, `WideDayView`.
- Create `composeApp/src/desktopTest/kotlin/fr/dayview/app/SettingsScreenTest.kt`.
- Create `composeApp/src/desktopTest/kotlin/fr/dayview/app/TodayScreenTest.kt`.
- Create `composeApp/src/desktopTest/kotlin/fr/dayview/app/FocusFlowTest.kt`.

---

### Task 1: Wire the Compose UI-test dependency + smoke test

De-risks the artifact/version before any real tests: proves `runComposeUiTest` compiles and runs on the desktop target without disturbing the Android/Robolectric run.

**Files:**
- Modify: `composeApp/build.gradle.kts` (sourceSets block, after the `desktopMain` block that ends at the line `        }` around line 97)
- Create: `composeApp/src/desktopTest/kotlin/fr/dayview/app/ComposeUiTestSmokeTest.kt`

**Interfaces:**
- Produces: a working `@OptIn(ExperimentalTestApi::class) runComposeUiTest { }` harness on `:composeApp:desktopTest`, with finders (`onNodeWithText`) and `setContent` available.

- [ ] **Step 1: Add the desktopTest dependency**

In `composeApp/build.gradle.kts`, inside `kotlin { sourceSets { ... } }`, add a new source-set block immediately after the existing `val desktopMain by getting { ... }` block:

```kotlin
        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.uiTestJUnit4)
            }
        }
```

(`compose.desktop.currentOs` is already available via `desktopMain`. The JUnit4 artifact transitively provides `runComposeUiTest` and the finders; the lambda form needs no JUnit4 rule. Contingency: if `runComposeUiTest` fails to resolve at compile time, also add `implementation(compose.uiTest)` to this same block.)

- [ ] **Step 2: Write the smoke test**

Create `composeApp/src/desktopTest/kotlin/fr/dayview/app/ComposeUiTestSmokeTest.kt`:

```kotlin
package fr.dayview.app

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ComposeUiTestSmokeTest {
    @Test
    fun harnessRendersAndFindsText() = runComposeUiTest {
        setContent { Text("dayview-smoke") }
        onNodeWithText("dayview-smoke").assertExists()
    }
}
```

- [ ] **Step 3: Run the smoke test — verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests 'fr.dayview.app.ComposeUiTestSmokeTest'`
Expected: BUILD SUCCESSFUL, the test executes and passes. (If it fails to resolve `runComposeUiTest`, apply the Step 1 contingency and re-run.)

- [ ] **Step 4: ktlint**

Run: `./gradlew ktlintCheck`
Expected: BUILD SUCCESSFUL (run `./gradlew ktlintFormat` first if it flags import order).

- [ ] **Step 5: Commit**

```bash
git add composeApp/build.gradle.kts composeApp/src/desktopTest/kotlin/fr/dayview/app/ComposeUiTestSmokeTest.kt
git commit -m "test: add Compose UI-test harness for the desktop target"
```

---

### Task 2: Shared test support, test tags, and Settings screen tests

Adds the shared `DayViewTestTags` object, the shared desktop-test helpers, the two Settings production tags, and the Settings test suite (recording-fake pattern).

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewSettingsScreen.kt` (add `testTag` import; tag the show-seconds Row ~line 149 and the back link ~line 94)
- Create: `composeApp/src/desktopTest/kotlin/fr/dayview/app/UiTestSupport.kt`
- Create: `composeApp/src/desktopTest/kotlin/fr/dayview/app/SettingsScreenTest.kt`

**Interfaces:**
- Produces:
  - `object DayViewTestTags { const val Countdown; FocusStart; FocusStop; SettingsShowSeconds; SettingsBack }` (all `String`).
  - `fun midWindowNow(): kotlin.time.Instant` — a `now` inside the default 08:00–18:00 window regardless of timezone.
  - `fun seededController(snapshot: DayPreferencesSnapshot, now: Instant = midWindowNow()): DayViewController`.
  - `fun noopSettingsActions(changeStartTime, changeEndTime, changeShowSeconds, changeSoundSettings, previewSound, back — all defaulted to no-ops): SettingsScreenActions`.

- [ ] **Step 1: Create the shared test-tags object**

Create `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt`:

```kotlin
package fr.dayview.app

/**
 * Stable identifiers for Compose UI tests (see composeApp/src/desktopTest).
 * Kept in commonMain so production composables and desktop tests share one
 * source of truth and cannot drift.
 */
internal object DayViewTestTags {
    const val Countdown = "dayViewCountdown"
    const val FocusStart = "focusStart"
    const val FocusStop = "focusStop"
    const val SettingsShowSeconds = "settingsShowSeconds"
    const val SettingsBack = "settingsBack"
}
```

- [ ] **Step 2: Tag the Settings show-seconds toggle and back link**

In `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewSettingsScreen.kt`:

Add the import (in alphabetical position among the `androidx.compose.ui.*` imports):

```kotlin
import androidx.compose.ui.platform.testTag
```

Tag the show-seconds toggle Row — change its modifier (currently starting `Modifier.fillMaxWidth()` then `.background(...)` then `.toggleable(...)` for the `state.showSeconds` toggle, ~line 149) so the chain reads:

```kotlin
                    modifier = Modifier.fillMaxWidth()
                        .testTag(DayViewTestTags.SettingsShowSeconds)
                        .background(colors.panel, RoundedCornerShape(18.dp))
                        .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
                        .toggleable(
                            value = state.showSeconds,
                            role = Role.Switch,
                            onValueChange = actions.changeShowSeconds,
                        )
                        .padding(horizontal = 16.dp, vertical = 14.dp),
```

Tag the back link — the `"‹  AUJOURD’HUI"` Text (~line 94), change its modifier chain to:

```kotlin
                    modifier = Modifier.minimumInteractiveComponentSize()
                        .testTag(DayViewTestTags.SettingsBack)
                        .clickable(role = Role.Button, onClick = actions.back)
                        .padding(vertical = 10.dp, horizontal = 4.dp),
```

- [ ] **Step 3: Create the shared desktop-test helpers**

Create `composeApp/src/desktopTest/kotlin/fr/dayview/app/UiTestSupport.kt`:

```kotlin
package fr.dayview.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A "now" that always falls inside the default 08:00–18:00 day window,
 * whatever the machine timezone, so day-progress labels ("IL RESTE") are
 * deterministic. Only the local date is taken from the system clock; it does
 * not affect the time-of-day assertions.
 */
internal fun midWindowNow(): Instant {
    val tz = TimeZone.currentSystemDefault()
    val localNow = Clock.System.now().toLocalDateTime(tz)
    return LocalDateTime(
        year = localNow.year,
        month = localNow.month,
        day = localNow.day,
        hour = 13,
        minute = 0,
    ).toInstant(tz)
}

/** Builds a controller from a seeded snapshot + fixed clock — the production path. */
internal fun seededController(
    snapshot: DayPreferencesSnapshot,
    now: Instant = midWindowNow(),
): DayViewController = DayViewController(
    preferences = InMemoryDayPreferences(snapshot),
    scope = CoroutineScope(Dispatchers.Unconfined),
    initialSnapshot = snapshot,
    initialNow = now,
)

/** A full [SettingsScreenActions] of no-ops with the relevant callbacks overridable. */
internal fun noopSettingsActions(
    changeStartTime: (Int) -> Unit = {},
    changeEndTime: (Int) -> Unit = {},
    changeShowSeconds: (Boolean) -> Unit = {},
    changeSoundSettings: (SoundSettings) -> Unit = {},
    previewSound: (SoundCue) -> Unit = {},
    back: () -> Unit = {},
): SettingsScreenActions = SettingsScreenActions(
    changeStartTime = changeStartTime,
    changeEndTime = changeEndTime,
    changeShowSeconds = changeShowSeconds,
    changeMonochromeMenuBarIcon = null,
    changeLaunchAtLogin = null,
    changeSoundSettings = changeSoundSettings,
    previewSound = previewSound,
    back = back,
)
```

- [ ] **Step 4: Write the Settings tests**

Create `composeApp/src/desktopTest/kotlin/fr/dayview/app/SettingsScreenTest.kt`:

```kotlin
package fr.dayview.app

import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {
    private val platform = SettingsPlatformUiState(monochromeMenuBarIcon = null, launchAtLogin = null)

    @Test
    fun rendersSeededDayRangeAndShowSeconds() = runComposeUiTest {
        val snapshot = DayPreferencesSnapshot(startMinutes = 8 * 60, endMinutes = 18 * 60, showSeconds = true)
        setContent {
            val state = remember { seededController(snapshot).state }
            DayViewTheme {
                SettingsScreen(state = state, platformState = platform, actions = noopSettingsActions())
            }
        }
        onNodeWithText("08:00").assertExists()
        onNodeWithText("18:00").assertExists()
        onNodeWithTag(DayViewTestTags.SettingsShowSeconds).assertIsOn()
    }

    @Test
    fun togglingShowSecondsInvokesCallback() = runComposeUiTest {
        val snapshot = DayPreferencesSnapshot(showSeconds = true)
        var recorded: Boolean? = null
        setContent {
            val state = remember { seededController(snapshot).state }
            DayViewTheme {
                SettingsScreen(
                    state = state,
                    platformState = platform,
                    actions = noopSettingsActions(changeShowSeconds = { recorded = it }),
                )
            }
        }
        onNodeWithTag(DayViewTestTags.SettingsShowSeconds).performClick()
        assertEquals(false, recorded)
    }

    @Test
    fun backLinkInvokesCallback() = runComposeUiTest {
        var backCalled = false
        setContent {
            val state = remember { seededController(DayPreferencesSnapshot()).state }
            DayViewTheme {
                SettingsScreen(
                    state = state,
                    platformState = platform,
                    actions = noopSettingsActions(back = { backCalled = true }),
                )
            }
        }
        onNodeWithTag(DayViewTestTags.SettingsBack).performClick()
        assertTrue(backCalled)
    }

    @Test
    fun rendersSoundPanel() = runComposeUiTest {
        setContent {
            val state = remember { seededController(DayPreferencesSnapshot()).state }
            DayViewTheme {
                SettingsScreen(state = state, platformState = platform, actions = noopSettingsActions())
            }
        }
        onNodeWithText("SONS").assertExists()
    }
}
```

- [ ] **Step 5: Run the Settings tests — verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests 'fr.dayview.app.SettingsScreenTest'`
Expected: BUILD SUCCESSFUL, 4 tests pass.

- [ ] **Step 6: ktlint**

Run: `./gradlew ktlintCheck`
Expected: BUILD SUCCESSFUL (`ktlintFormat` first if needed).

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewSettingsScreen.kt \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/UiTestSupport.kt \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/SettingsScreenTest.kt
git commit -m "test: cover the Settings screen with Compose UI tests"
```

---

### Task 3: Today screen rendering tests

Adds the three Today-screen production tags, the Today-facing helpers (`noopDayViewActions`, `noReminders`, `WideDayView`), and the Today rendering suite (idle + active focus states). `WideDayView` forces the wide layout so the Goal/Focus panels render inline.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (add `testTag` import; tag `CountdownCircle` root ~line 564, the focus-start button ~line 1164, the active focus-stop button ~line 1024)
- Modify: `composeApp/src/desktopTest/kotlin/fr/dayview/app/UiTestSupport.kt` (append the Today helpers)
- Create: `composeApp/src/desktopTest/kotlin/fr/dayview/app/TodayScreenTest.kt`

**Interfaces:**
- Consumes: `DayViewTestTags`, `seededController`, `midWindowNow` (Task 2).
- Produces:
  - `fun noopDayViewActions(openSettings, changeFocusIntention, changePomodoroDuration, startPomodoro, stopPomodoro, closePomodoro — all defaulted to no-ops): DayViewScreenActions`.
  - `fun noReminders(): FocusReminderUiState` (all flags false, dismiss = no-ops).
  - `@Composable fun WideDayView(state: DayViewUiState, actions: DayViewScreenActions, reminders: FocusReminderUiState = noReminders())` — wraps `DayViewScreen` in `DayViewTheme` + a `requiredSize(1100.dp, 900.dp)` Box to force wide layout.

- [ ] **Step 1: Tag the countdown root and the two focus buttons**

In `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt`, add the import (alphabetical position among `androidx.compose.ui.*`):

```kotlin
import androidx.compose.ui.platform.testTag
```

Tag the `CountdownCircle` root `Box` (~line 564) — change:

```kotlin
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
```

to:

```kotlin
    Box(modifier = modifier.testTag(DayViewTestTags.Countdown), contentAlignment = Alignment.Center) {
```

Tag the focus-start button inside `FocusCreationContent` (~line 1164) — change:

```kotlin
    FocusActionButton(
        "DÉMARRER LE FOCUS",
        colors.amber,
        modifier = Modifier.fillMaxWidth(),
        enabled = intention.isNotBlank(),
        filled = true,
        onClick = onStart,
    )
```

to:

```kotlin
    FocusActionButton(
        "DÉMARRER LE FOCUS",
        colors.amber,
        modifier = Modifier.fillMaxWidth().testTag(DayViewTestTags.FocusStart),
        enabled = intention.isNotBlank(),
        filled = true,
        onClick = onStart,
    )
```

Tag the active focus-stop button (the one in the normal ACTIVE block ~line 1024, `onClick = onStop`, NOT the resume-ritual one) — change:

```kotlin
                FocusActionButton("ARRÊTER", colors.red, onClick = onStop)
```

to:

```kotlin
                FocusActionButton(
                    "ARRÊTER",
                    colors.red,
                    modifier = Modifier.testTag(DayViewTestTags.FocusStop),
                    onClick = onStop,
                )
```

- [ ] **Step 2: Append the Today helpers to UiTestSupport.kt**

Add to `composeApp/src/desktopTest/kotlin/fr/dayview/app/UiTestSupport.kt` — new imports (keep alphabetical) and new members:

```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
```

```kotlin
/** A full [DayViewScreenActions] of no-ops with the relevant callbacks overridable. */
internal fun noopDayViewActions(
    openSettings: () -> Unit = {},
    changeFocusIntention: (String) -> Unit = {},
    changePomodoroDuration: (Int) -> Unit = {},
    startPomodoro: () -> Unit = {},
    stopPomodoro: () -> Unit = {},
    closePomodoro: (FocusClosureOutcome) -> Unit = {},
): DayViewScreenActions = DayViewScreenActions(
    openSettings = openSettings,
    openMiniWindow = null,
    changeGoalTitle = {},
    changeGoalDeadline = {},
    commitGoalDeadline = {},
    changeGoalStart = {},
    commitGoalStart = {},
    changeFocusIntention = changeFocusIntention,
    changePomodoroDuration = changePomodoroDuration,
    startPomodoro = startPomodoro,
    stopPomodoro = stopPomodoro,
    closePomodoro = closePomodoro,
)

internal fun noReminders(): FocusReminderUiState = FocusReminderUiState(
    showDriftReminder = false,
    dismissDriftReminder = {},
    showResumeRitual = false,
    dismissResumeRitual = {},
)

/** Renders [DayViewScreen] forced into the wide layout so Goal/Focus panels render inline. */
@Composable
internal fun WideDayView(
    state: DayViewUiState,
    actions: DayViewScreenActions,
    reminders: FocusReminderUiState = noReminders(),
) {
    DayViewTheme {
        Box(Modifier.requiredSize(1100.dp, 900.dp)) {
            DayViewScreen(state = state, actions = actions, reminders = reminders)
        }
    }
}
```

- [ ] **Step 3: Write the Today rendering tests**

Create `composeApp/src/desktopTest/kotlin/fr/dayview/app/TodayScreenTest.kt`:

```kotlin
package fr.dayview.app

import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalTestApi::class)
class TodayScreenTest {
    @Test
    fun rendersCountdownGoalAndFocusEntry() = runComposeUiTest {
        val snapshot = DayPreferencesSnapshot(goalTitle = "Livrer la v2")
        setContent {
            val state = remember { seededController(snapshot).state }
            WideDayView(state = state, actions = noopDayViewActions())
        }
        onNodeWithTag(DayViewTestTags.Countdown).assertExists()
        onNodeWithText("IL RESTE").assertExists()
        onNodeWithText("Livrer la v2").assertExists()
        onNodeWithTag(DayViewTestTags.FocusStart).assertExists()
    }

    @Test
    fun rendersActiveFocusState() = runComposeUiTest {
        val now = midWindowNow()
        val snapshot = DayPreferencesSnapshot(
            focusIntention = "Écrire le rapport",
            pomodoroEnd = now + 25.minutes,
        )
        setContent {
            val state = remember { seededController(snapshot, now).state }
            WideDayView(state = state, actions = noopDayViewActions())
        }
        onNodeWithText("Écrire le rapport").assertExists()
        onNodeWithTag(DayViewTestTags.FocusStop).assertExists()
    }
}
```

- [ ] **Step 4: Run the Today tests — verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests 'fr.dayview.app.TodayScreenTest'`
Expected: BUILD SUCCESSFUL, 2 tests pass. (If `onNodeWithText("Livrer la v2")` cannot find the editable field value, replace that line with `onNodeWithContentDescription("Objectif du jour").assertExists()` — the field's existing accessibility label.)

- [ ] **Step 5: ktlint**

Run: `./gradlew ktlintCheck`
Expected: BUILD SUCCESSFUL (`ktlintFormat` first if needed).

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/UiTestSupport.kt \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/TodayScreenTest.kt
git commit -m "test: cover the Today screen rendering with Compose UI tests"
```

---

### Task 4: Focus start/stop flow tests + full verification gate

Adds the controller-wired action bundle and the Focus interaction suite (asserts real controller-state transitions), then runs the full lint + test gate.

**Files:**
- Modify: `composeApp/src/desktopTest/kotlin/fr/dayview/app/UiTestSupport.kt` (append `controllerDayViewActions`)
- Create: `composeApp/src/desktopTest/kotlin/fr/dayview/app/FocusFlowTest.kt`

**Interfaces:**
- Consumes: `DayViewTestTags`, `seededController`, `midWindowNow`, `noopDayViewActions`, `WideDayView` (Tasks 2–3).
- Produces: `fun controllerDayViewActions(controller: DayViewController): DayViewScreenActions` — wires every callback to the controller (mirrors `App.kt`, minus the platform focus-alarm hook).

- [ ] **Step 1: Append the controller-wired action bundle to UiTestSupport.kt**

Add to `composeApp/src/desktopTest/kotlin/fr/dayview/app/UiTestSupport.kt`:

```kotlin
/** Wires a [DayViewScreenActions] bundle straight to the controller (as App.kt does). */
internal fun controllerDayViewActions(controller: DayViewController): DayViewScreenActions =
    DayViewScreenActions(
        openSettings = { controller.openSettings() },
        openMiniWindow = null,
        changeGoalTitle = { controller.setGoalTitle(it) },
        changeGoalDeadline = { controller.setGoalDeadlineText(it) },
        commitGoalDeadline = { controller.commitGoalDeadline() },
        changeGoalStart = { controller.setGoalStartText(it) },
        commitGoalStart = { controller.commitGoalStart() },
        changeFocusIntention = { controller.setFocusIntention(it) },
        changePomodoroDuration = { controller.changePomodoroDuration(it) },
        startPomodoro = { controller.startPomodoro() },
        stopPomodoro = { controller.stopPomodoro() },
        closePomodoro = { controller.closePomodoro(it) },
    )
```

- [ ] **Step 2: Write the Focus flow tests**

Create `composeApp/src/desktopTest/kotlin/fr/dayview/app/FocusFlowTest.kt`:

```kotlin
package fr.dayview.app

import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalTestApi::class)
class FocusFlowTest {
    @Test
    fun startFocusInvokesController() = runComposeUiTest {
        val now = midWindowNow()
        val snapshot = DayPreferencesSnapshot(focusIntention = "Écrire le rapport")
        lateinit var controller: DayViewController
        setContent {
            val c = remember { seededController(snapshot, now) }
            controller = c
            WideDayView(state = c.state, actions = controllerDayViewActions(c))
        }
        onNodeWithTag(DayViewTestTags.FocusStart).performClick()
        assertTrue(controller.state.focusIsActive)
    }

    @Test
    fun stopFocusInvokesController() = runComposeUiTest {
        val now = midWindowNow()
        val snapshot = DayPreferencesSnapshot(
            focusIntention = "Écrire le rapport",
            pomodoroEnd = now + 25.minutes,
        )
        lateinit var controller: DayViewController
        setContent {
            val c = remember { seededController(snapshot, now) }
            controller = c
            WideDayView(state = c.state, actions = controllerDayViewActions(c))
        }
        assertTrue(controller.state.focusIsActive)
        onNodeWithTag(DayViewTestTags.FocusStop).performClick()
        assertFalse(controller.state.focusIsActive)
    }

    @Test
    fun startDisabledWhenIntentionBlank() = runComposeUiTest {
        val snapshot = DayPreferencesSnapshot(focusIntention = "")
        setContent {
            val state = remember { seededController(snapshot).state }
            WideDayView(state = state, actions = noopDayViewActions())
        }
        onNodeWithTag(DayViewTestTags.FocusStart).assertIsNotEnabled()
        onNodeWithText("Écrivez une intention pour démarrer.").assertExists()
    }
}
```

- [ ] **Step 3: Run the Focus tests — verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests 'fr.dayview.app.FocusFlowTest'`
Expected: BUILD SUCCESSFUL, 3 tests pass.

- [ ] **Step 4: Run the full verification gate**

Run: `./gradlew ktlintCheck :composeApp:desktopTest :composeApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — ktlint clean, all desktop tests (including the new UI suites) green, and the Robolectric Android unit tests still green.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/desktopTest/kotlin/fr/dayview/app/UiTestSupport.kt \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/FocusFlowTest.kt
git commit -m "test: cover the Focus start/stop flow with Compose UI tests"
```

---

## Coverage note (for the PR description)

- **Covered:** Settings (day-range + show-seconds render, show-seconds callback, back callback, sound panel render); Today (countdown ring, seeded goal, focus entry, active-focus render); Focus (start via controller, stop via controller, start disabled when intention blank).
- **Deferred:** `DayViewApp` end-to-end (non-deterministic clock + infinite ticker); Net-time & On-goal-apps settings panels (desktop-gated behind calendar/running-app platform state); compact modal-bottom-sheet flows; `DayViewMiniApp`; exact numeric countdown & timezone-sensitive states.

## Self-Review

**Spec coverage:** Placement/desktopTest → Task 1. Dependency (`compose.uiTest`/fallback) → Task 1 Step 1. Test seam via pure screens + `DayViewController` + `InMemoryDayPreferences` → Tasks 2–4. Recording-fake vs controller-wired patterns → Tasks 2 / 4. `DayViewTheme` wrapper + wide-layout forcing → `WideDayView` (Task 3). Timezone-safe `now` → `midWindowNow` (Task 2). Minimal tags (5) → Tasks 2–3. Three test files + helper → Tasks 2–4. Deferred list → Coverage note. Verification gate → Task 4 Step 4. No gaps.

**Placeholder scan:** No TBD/TODO; every code step shows full code; the only contingencies (dependency fallback in Task 1, `contentDescription` fallback in Task 3) are explicit, verifiable alternatives, not deferred work.

**Type consistency:** `seededController(snapshot, now)`, `midWindowNow(): Instant`, `noopSettingsActions(...)`, `noopDayViewActions(...)`, `noReminders()`, `WideDayView(state, actions, reminders)`, `controllerDayViewActions(controller)`, and `DayViewTestTags.{Countdown,FocusStart,FocusStop,SettingsShowSeconds,SettingsBack}` are named identically at definition and every use site. `SettingsScreenActions` / `DayViewScreenActions` / `FocusReminderUiState` / `SettingsPlatformUiState` field names match the source (App.kt, DayViewSettingsScreen.kt, DayViewTodayScreen.kt).
