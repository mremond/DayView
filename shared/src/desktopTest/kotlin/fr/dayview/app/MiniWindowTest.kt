package fr.dayview.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalTestApi::class)
class MiniWindowTest {
    @Test
    fun openMainWindowButtonInvokesCallback() = runComposeUiTest {
        var mainOpened = false
        val now = midWindowNow()
        setContent {
            DayViewMiniApp(
                progress = calculateDayProgress(now, 8 * 60, 18 * 60),
                showSeconds = false,
                now = now,
                goalTitle = "",
                goalDeadline = null,
                pomodoro = calculatePomodoroProgress(now, 25, null),
                focusIntention = "",
                onStartFocus = {},
                onCloseFocus = { _, _, _, _ -> },
                onOpenMainWindow = { mainOpened = true },
            )
        }
        onNodeWithTag(DayViewTestTags.OpenMainWindow).assertExists()
        onNodeWithTag(DayViewTestTags.OpenMainWindow).performClick()
        assertTrue(mainOpened)
    }

    @Test
    fun startingFocusFromIntentionModalNeedsNoIntention() = runComposeUiTest {
        var started: String? = null
        val now = midWindowNow()
        setContent {
            DayViewMiniApp(
                progress = calculateDayProgress(now, 8 * 60, 18 * 60),
                showSeconds = false,
                now = now,
                goalTitle = "",
                goalDeadline = null,
                pomodoro = calculatePomodoroProgress(now, 25, null),
                focusIntention = "",
                onStartFocus = { started = it },
                onCloseFocus = { _, _, _, _ -> },
                onOpenMainWindow = {},
            )
        }
        onNodeWithTag(DayViewTestTags.MiniFocusStart).performClick()
        onNodeWithTag(DayViewTestTags.MiniFocusConfirm).assertIsEnabled()
        onNodeWithTag(DayViewTestTags.MiniFocusConfirm).performClick()
        assertEquals("", started)
    }

    @Test
    fun startAffordanceIsHiddenWhileAnOpenDetourRuns() = runComposeUiTest {
        val now = midWindowNow()
        setContent {
            DayViewMiniApp(
                progress = calculateDayProgress(now, 8 * 60, 18 * 60),
                showSeconds = false,
                now = now,
                goalTitle = "",
                goalDeadline = null,
                pomodoro = calculatePomodoroProgress(now, 25, null),
                focusIntention = "",
                openDetourRunning = true,
                onStartFocus = {},
                onCloseFocus = { _, _, _, _ -> },
                onOpenMainWindow = {},
            )
        }
        // Prove the mini window composed at all (sibling in the same branch) before
        // asserting the Start affordance's absence — otherwise this would pass vacuously
        // if the whole tree failed to render.
        onNodeWithTag(DayViewTestTags.OpenMainWindow).assertExists()
        onNodeWithTag(DayViewTestTags.MiniFocusStart).assertDoesNotExist()
    }

    @Test
    fun relaunchButtonDuringBreakStartsNextFocusWithSameIntention() = runComposeUiTest {
        var startedIntention: String? = null
        val now = midWindowNow()
        setContent {
            DayViewMiniApp(
                progress = calculateDayProgress(now, 8 * 60, 18 * 60),
                showSeconds = false,
                now = now,
                goalTitle = "",
                goalDeadline = null,
                pomodoro = calculatePomodoroProgress(now, 25, null, breakStart = now - 1.minutes),
                focusIntention = "Couper 5k secs",
                onStartFocus = { startedIntention = it },
                onCloseFocus = { _, _, _, _ -> },
                onOpenMainWindow = {},
            )
        }
        onNodeWithTag(DayViewTestTags.MiniFocusRelaunch).assertExists()
        onNodeWithTag(DayViewTestTags.MiniFocusRelaunch).performClick()
        assertEquals("Couper 5k secs", startedIntention)
    }

    @Test
    fun relaunchButtonIsHiddenWhileAnOpenDetourRuns() = runComposeUiTest {
        val now = midWindowNow()
        setContent {
            DayViewMiniApp(
                progress = calculateDayProgress(now, 8 * 60, 18 * 60),
                showSeconds = false,
                now = now,
                goalTitle = "",
                goalDeadline = null,
                pomodoro = calculatePomodoroProgress(now, 25, null, breakStart = now - 1.minutes),
                focusIntention = "Couper 5k secs",
                openDetourRunning = true,
                onStartFocus = {},
                onCloseFocus = { _, _, _, _ -> },
                onOpenMainWindow = {},
            )
        }
        // Prove the mini window composed at all (sibling in the same branch) before
        // asserting the relaunch control's absence — otherwise this would pass vacuously
        // if the whole tree failed to render.
        onNodeWithTag(DayViewTestTags.OpenMainWindow).assertExists()
        onNodeWithTag(DayViewTestTags.MiniFocusRelaunch).assertDoesNotExist()
    }

    @Test
    fun relaunchAndClosureAreHiddenWhileFocusIsActive() = runComposeUiTest {
        val now = midWindowNow()
        setContent {
            DayViewMiniApp(
                progress = calculateDayProgress(now, 8 * 60, 18 * 60),
                showSeconds = false,
                now = now,
                goalTitle = "",
                goalDeadline = null,
                pomodoro = calculatePomodoroProgress(now, 25, now + 10.minutes),
                focusIntention = "Couper 5k secs",
                onStartFocus = {},
                onCloseFocus = { _, _, _, _ -> },
                onOpenMainWindow = {},
            )
        }
        // Prove the running-session card is composed before asserting absences.
        onNodeWithTag(DayViewTestTags.MiniFocusStop).assertExists()
        onNodeWithTag(DayViewTestTags.MiniFocusRelaunch).assertDoesNotExist()
        onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.COMPLETED)).assertDoesNotExist()
    }

    @Test
    fun stoppingARunningFocusOpensTheClosureSheetAndCostsADetour() = runComposeUiTest {
        var closed: List<Any?>? = null
        val now = midWindowNow()
        setContent {
            DayViewMiniApp(
                progress = calculateDayProgress(now, 8 * 60, 18 * 60),
                showSeconds = false,
                now = now,
                goalTitle = "",
                goalDeadline = null,
                pomodoro = calculatePomodoroProgress(now, 25, now + 10.minutes),
                focusIntention = "Couper 5k secs",
                onStartFocus = {},
                onCloseFocus = { outcome, intention, category, description ->
                    closed = listOf(outcome, intention, category, description)
                },
                onOpenMainWindow = {},
            )
        }
        onNodeWithTag(DayViewTestTags.MiniFocusStop).performClick()
        onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.PROGRESSED)).performClick()
        // Fleeing before the term reports nothing until the pull is named.
        assertNull(closed)
        onNodeWithTag(DayViewTestTags.FocusExitDetourCategory).performTextInput("Mail")
        onNodeWithTag(DayViewTestTags.FocusExitDetourConfirm).performClick()
        assertEquals(listOf(FocusClosureOutcome.PROGRESSED, "Couper 5k secs", "Mail", ""), closed)
    }

    @Test
    fun closureButtonsDuringOvertimeReportTheOutcome() = runComposeUiTest {
        var closedWith: FocusClosureOutcome? = null
        val now = midWindowNow()
        setContent {
            DayViewMiniApp(
                progress = calculateDayProgress(now, 8 * 60, 18 * 60),
                showSeconds = false,
                now = now,
                goalTitle = "",
                goalDeadline = null,
                pomodoro = calculatePomodoroProgress(now, 25, now - 1.minutes),
                focusIntention = "Couper 5k secs",
                onStartFocus = {},
                onCloseFocus = { outcome, _, _, _ -> closedWith = outcome },
                onOpenMainWindow = {},
            )
        }
        onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.TO_RESUME)).assertExists()
        onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.COMPLETED)).performClick()
        assertEquals(FocusClosureOutcome.COMPLETED, closedWith)
    }

    @Test
    fun crossingTheTermDropsADetourTollNoLongerOwed() = runComposeUiTest {
        val start = midWindowNow()
        val end = start + 1.minutes
        var now by mutableStateOf(start)
        setContent {
            DayViewMiniApp(
                progress = calculateDayProgress(now, 8 * 60, 18 * 60),
                showSeconds = false,
                now = now,
                goalTitle = "",
                goalDeadline = null,
                pomodoro = calculatePomodoroProgress(now, 25, end),
                focusIntention = "Couper 5k secs",
                onStartFocus = {},
                onCloseFocus = { _, _, _, _ -> },
                onOpenMainWindow = {},
            )
        }
        onNodeWithTag(DayViewTestTags.MiniFocusStop).performClick()
        onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.PROGRESSED)).performClick()
        onNodeWithTag(DayViewTestTags.FocusExitDetourCategory).assertExists()
        // The term passes while the detour capture is open. Overtime owes no name, and the
        // mini window's Cancel disappears with it, so a capture left standing here would be
        // a charge with no way out.
        now = end + 1.minutes
        waitForIdle()
        // The sheet stays — overtime is where it lives …
        onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.PROGRESSED)).assertExists()
        // … and the toll folds away with the obligation.
        onNodeWithTag(DayViewTestTags.FocusExitDetourCategory).assertDoesNotExist()
        onNodeWithTag(DayViewTestTags.FocusExitDetourConfirm).assertDoesNotExist()
    }

    @Test
    fun crossingTheTermKeepsTheIntentionBeingTypedIntoTheClosureSheet() = runComposeUiTest {
        // Mirrors FocusFlowTest's identity check for the wide window: without it, the toll
        // test above could pass for the wrong reason — if the closure sheet lost its state
        // entirely across the crossing (rather than only its detour toll), pendingOutcome
        // would reset on its own and the detour fields would be absent, but so would this
        // half-typed intention, which the crossing must not throw away.
        val start = midWindowNow()
        val end = start + 1.minutes
        var now by mutableStateOf(start)
        setContent {
            DayViewMiniApp(
                progress = calculateDayProgress(now, 8 * 60, 18 * 60),
                showSeconds = false,
                now = now,
                goalTitle = "",
                goalDeadline = null,
                pomodoro = calculatePomodoroProgress(now, 25, end),
                focusIntention = "",
                onStartFocus = {},
                onCloseFocus = { _, _, _, _ -> },
                onOpenMainWindow = {},
            )
        }
        onNodeWithTag(DayViewTestTags.MiniFocusStop).performClick()
        onNodeWithTag(DayViewTestTags.FocusClosureIntention).performTextInput("Écrire le rapport")
        // The term passes while the intention is half-typed. Capturing it is the whole point
        // of moving the naming to the close, so the crossing must not throw it away.
        now = end + 1.minutes
        waitForIdle()
        // Prove the OVERTIME branch composed before reading anything out of it.
        onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.COMPLETED)).assertExists()
        onNodeWithTag(DayViewTestTags.FocusClosureIntention).assertTextContains("Écrire le rapport")
    }

    @Test
    fun closureControlsAreAbsentDuringBreak() = runComposeUiTest {
        val now = midWindowNow()
        setContent {
            DayViewMiniApp(
                progress = calculateDayProgress(now, 8 * 60, 18 * 60),
                showSeconds = false,
                now = now,
                goalTitle = "",
                goalDeadline = null,
                pomodoro = calculatePomodoroProgress(now, 25, null, breakStart = now - 1.minutes),
                focusIntention = "Couper 5k secs",
                onStartFocus = {},
                onCloseFocus = { _, _, _, _ -> },
                onOpenMainWindow = {},
            )
        }
        // Relaunch is BREAK-only and proves the branch renders.
        onNodeWithTag(DayViewTestTags.MiniFocusRelaunch).assertExists()
        // The session is already closed: nothing left to stop or to close.
        onNodeWithTag(DayViewTestTags.MiniFocusStop).assertDoesNotExist()
        onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.COMPLETED)).assertDoesNotExist()
    }

    @Test
    fun minimumMiniWindowKeepsPrimaryControlsAndHidesGoalAtLargeText() = runComposeUiTest {
        val now = midWindowNow()
        setContent {
            VisualViewport(width = 200.dp, height = 300.dp) {
                DayViewMiniApp(
                    progress = calculateDayProgress(now, 8 * 60, 18 * 60),
                    showSeconds = false,
                    now = now,
                    goalTitle = "Livrer la version du jour",
                    goalDeadline = null,
                    pomodoro = calculatePomodoroProgress(now, 25, null),
                    focusIntention = "",
                    fontScale = 1.5f,
                    onStartFocus = {},
                    onCloseFocus = { _, _, _, _ -> },
                    onOpenMainWindow = {},
                )
            }
        }

        captureVisual("mini-window-minimum-200x300-font150")
        onNodeWithTag(DayViewTestTags.MiniGoal).assertDoesNotExist()
        onNodeWithTag(DayViewTestTags.OpenMainWindow).assertIsDisplayed().assertInsideVisualViewport(this)
        onNodeWithTag(DayViewTestTags.MiniFocusStart).assertIsDisplayed().assertInsideVisualViewport(this)
    }

    @Test
    fun minimumMiniWindowKeepsFocusFormActionsVisible() = runComposeUiTest {
        val now = midWindowNow()
        setContent {
            VisualViewport(width = 200.dp, height = 300.dp) {
                DayViewMiniApp(
                    progress = calculateDayProgress(now, 8 * 60, 18 * 60),
                    showSeconds = false,
                    now = now,
                    goalTitle = "",
                    goalDeadline = null,
                    pomodoro = calculatePomodoroProgress(now, 25, null),
                    focusIntention = "",
                    onStartFocus = {},
                    onCloseFocus = { _, _, _, _ -> },
                    onOpenMainWindow = {},
                )
            }
        }

        onNodeWithTag(DayViewTestTags.MiniFocusStart).performClick()
        captureVisual("mini-window-focus-form-minimum-200x300")
        onNodeWithTag(DayViewTestTags.MiniFocusModal).assertIsDisplayed().assertInsideVisualViewport(this)
        onNodeWithTag(DayViewTestTags.MiniFocusConfirm).assertIsDisplayed().assertInsideVisualViewport(this)
        onNodeWithTag(DayViewTestTags.MiniFocusCancel).assertIsDisplayed().assertInsideVisualViewport(this)
    }
}
