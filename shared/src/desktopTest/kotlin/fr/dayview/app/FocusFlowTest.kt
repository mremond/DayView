package fr.dayview.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        onNodeWithTag(DayViewTestTags.FocusEntry).performScrollTo().performClick()
        onNodeWithTag(DayViewTestTags.FocusStart).performScrollTo().performClick()
        assertTrue(controller.state.focusIsActive)
    }

    @Test
    fun commandEnterStartsReadyFocus() = runComposeUiTest {
        val snapshot = DayPreferencesSnapshot(focusIntention = "Écrire le rapport")
        lateinit var controller: DayViewController
        setContent {
            val c = remember { seededController(snapshot) }
            controller = c
            WideDayView(state = c.state, actions = controllerDayViewActions(c))
        }
        onNodeWithTag(DayViewTestTags.FocusEntry).performScrollTo().performClick()
        onNodeWithTag(DayViewTestTags.FocusStart).requestFocus().performKeyInput {
            keyDown(Key.MetaLeft)
            pressKey(Key.Enter)
            keyUp(Key.MetaLeft)
        }
        assertTrue(controller.state.focusIsActive)
    }

    @Test
    fun horizontalArrowsAdjustFocusedDurationControl() = runComposeUiTest {
        val snapshot = DayPreferencesSnapshot(focusIntention = "Écrire le rapport", pomodoroMinutes = 25)
        lateinit var controller: DayViewController
        setContent {
            val c = remember { seededController(snapshot) }
            controller = c
            WideDayView(state = c.state, actions = controllerDayViewActions(c))
        }
        onNodeWithTag(DayViewTestTags.FocusEntry).performScrollTo().performClick()
        onNodeWithTag(DayViewTestTags.FocusDurationDecrease).requestFocus().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        assertEquals(30, controller.state.pomodoroProgress.durationMinutes)
        onNodeWithTag(DayViewTestTags.FocusDurationDecrease).performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        assertEquals(25, controller.state.pomodoroProgress.durationMinutes)
    }

    @Test
    fun stopDuringActiveOpensClosureNotAbort() = runComposeUiTest {
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
        onNodeWithTag(DayViewTestTags.FocusStop).performScrollTo().performClick()
        // Stop opens the closure sheet; it never aborts the session by itself.
        assertNotNull(controller.state.pomodoroEnd)
        assertTrue(controller.state.focusIsActive)
        onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.COMPLETED)).performScrollTo().assertExists()
    }

    @Test
    fun completingEarlyClosesWithoutNamingADetour() = runComposeUiTest {
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
        onNodeWithTag(DayViewTestTags.FocusStop).performScrollTo().performClick()
        onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.COMPLETED)).performScrollTo().performClick()
        // Finishing is free at any moment: no toll, and the closure anchors a break.
        assertNull(controller.state.pomodoroEnd)
        assertNotNull(controller.state.breakStart)
    }

    @Test
    fun earlyProgressedDemandsCategoryBeforeClosing() = runComposeUiTest {
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
        onNodeWithTag(DayViewTestTags.FocusStop).performScrollTo().performClick()
        onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.PROGRESSED)).performScrollTo().performClick()
        // Fleeing before the term costs a name: the outcome alone closes nothing.
        assertNotNull(controller.state.pomodoroEnd)
        onNodeWithTag(DayViewTestTags.FocusExitDetourCategory).performScrollTo().performTextInput("Mail")
        onNodeWithTag(DayViewTestTags.FocusExitDetourConfirm).performScrollTo().performClick()
        assertNull(controller.state.pomodoroEnd)
        assertEquals("Mail", controller.state.openDetourCategory)
    }

    @Test
    fun aCategoryTheSanitizerEmptiesNeverEnablesConfirm() = runComposeUiTest {
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
        onNodeWithTag(DayViewTestTags.FocusStop).performScrollTo().performClick()
        onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.PROGRESSED)).performScrollTo().performClick()
        // A comma is stripped by the category sanitizer, so this names nothing. Confirm must
        // say so by staying disabled rather than accepting a click that changes nothing.
        onNodeWithTag(DayViewTestTags.FocusExitDetourCategory).performScrollTo().performTextInput(",")
        onNodeWithTag(DayViewTestTags.FocusExitDetourConfirm).performScrollTo().assertIsNotEnabled()
        assertNotNull(controller.state.pomodoroEnd)
        // A real name enables it again.
        onNodeWithTag(DayViewTestTags.FocusExitDetourCategory).performTextInput("Mail")
        onNodeWithTag(DayViewTestTags.FocusExitDetourConfirm).performScrollTo().assertIsEnabled()
    }

    @Test
    fun stayingCancelsTheEarlyExitAndKeepsTheSessionRunning() = runComposeUiTest {
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
        onNodeWithTag(DayViewTestTags.FocusStop).performScrollTo().performClick()
        onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.TO_RESUME)).performScrollTo().performClick()
        onNodeWithTag(DayViewTestTags.FocusExitCancel).performScrollTo().performClick()
        assertTrue(controller.state.focusIsActive)
        // Staying folds the whole sheet away — the Stop button is back on its own.
        onNodeWithTag(DayViewTestTags.FocusStop).performScrollTo().assertExists()
        onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.COMPLETED)).assertDoesNotExist()
    }

    @Test
    fun overtimePanelShowsClosureWithoutStopRouting() = runComposeUiTest {
        val now = midWindowNow()
        val snapshot = DayPreferencesSnapshot(
            focusIntention = "Écrire le rapport",
            pomodoroEnd = now - 1.minutes,
        )
        lateinit var controller: DayViewController
        setContent {
            val c = remember { seededController(snapshot, now) }
            controller = c
            WideDayView(state = c.state, actions = controllerDayViewActions(c))
        }
        assertEquals(PomodoroStatus.OVERTIME, controller.state.pomodoroProgress.status)
        // Overtime needs no Stop: the closure is the only way out and is always offered.
        onNodeWithTag(DayViewTestTags.FocusStop).assertDoesNotExist()
        onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.COMPLETED)).performScrollTo().performClick()
        assertNull(controller.state.pomodoroEnd)
        assertNotNull(controller.state.breakStart)
    }

    @Test
    fun closureCarriesTheIntentionNamedAtClose() = runComposeUiTest {
        val now = midWindowNow()
        val snapshot = DayPreferencesSnapshot(
            focusIntention = "",
            pomodoroEnd = now - 1.minutes,
        )
        lateinit var controller: DayViewController
        setContent {
            val c = remember { seededController(snapshot, now) }
            controller = c
            WideDayView(state = c.state, actions = controllerDayViewActions(c))
        }
        // Naming the work is asked at the close, not at the start.
        onNodeWithTag(DayViewTestTags.FocusClosureIntention).performScrollTo().performTextInput("Écrire le rapport")
        // TO_RESUME keeps the intention, so the named text is what survives the closure.
        onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.TO_RESUME)).performScrollTo().performClick()
        assertNull(controller.state.pomodoroEnd)
        assertEquals("Écrire le rapport", controller.state.focusIntention)
    }

    @Test
    fun closureIntentionFieldStartsFromTheRunningIntention() = runComposeUiTest {
        val now = midWindowNow()
        val snapshot = DayPreferencesSnapshot(
            focusIntention = "Écrire le rapport",
            pomodoroEnd = now - 1.minutes,
        )
        setContent {
            val c = remember { seededController(snapshot, now) }
            WideDayView(state = c.state, actions = controllerDayViewActions(c))
        }
        onNodeWithTag(DayViewTestTags.FocusClosureIntention).performScrollTo().assertTextContains("Écrire le rapport")
    }

    @Test
    fun relaunchDuringBreakStartsNextFocusWithSameIntention() = runComposeUiTest {
        val now = midWindowNow()
        val snapshot = DayPreferencesSnapshot(
            focusIntention = "Écrire le rapport",
            breakStart = now - 1.minutes,
        )
        lateinit var controller: DayViewController
        setContent {
            val c = remember { seededController(snapshot, now) }
            controller = c
            WideDayView(state = c.state, actions = controllerDayViewActions(c))
        }
        assertEquals(PomodoroStatus.BREAK, controller.state.pomodoroProgress.status)
        onNodeWithTag(DayViewTestTags.FocusRelaunch).performScrollTo().performClick()
        assertTrue(controller.state.focusIsActive)
        assertEquals("Écrire le rapport", controller.state.focusIntention)
    }

    @Test
    fun relaunchIsAbsentDuringOvertime() = runComposeUiTest {
        val now = midWindowNow()
        val snapshot = DayPreferencesSnapshot(
            focusIntention = "Écrire le rapport",
            pomodoroEnd = now - 1.minutes,
        )
        lateinit var controller: DayViewController
        setContent {
            val c = remember { seededController(snapshot, now) }
            controller = c
            WideDayView(state = c.state, actions = controllerDayViewActions(c))
        }
        // A session is still running in overtime, so relaunching makes no sense — and
        // startPomodoro refuses to start a second session over it anyway.
        assertEquals(PomodoroStatus.OVERTIME, controller.state.pomodoroProgress.status)
        // Prove the OVERTIME branch is actually composed before asserting an absence.
        onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.COMPLETED)).performScrollTo().assertExists()
        onNodeWithTag(DayViewTestTags.FocusRelaunch).assertDoesNotExist()
    }

    @Test
    fun crossingTheTermKeepsTheIntentionBeingTypedIntoTheClosureSheet() = runComposeUiTest {
        val start = midWindowNow()
        val end = start + 1.minutes
        var state by mutableStateOf(focusUiState(now = start, pomodoroEnd = end))
        setContent { WideDayView(state = state, actions = noopDayViewActions()) }
        onNodeWithTag(DayViewTestTags.FocusStop).performScrollTo().performClick()
        onNodeWithTag(DayViewTestTags.FocusClosureIntention).performScrollTo().performTextInput("Écrire le rapport")
        // The term passes while the intention is half-typed. Capturing it is the whole point
        // of moving the naming to the close, so the crossing must not throw it away.
        state = focusUiState(now = end + 1.minutes, pomodoroEnd = end)
        waitForIdle()
        // Prove the OVERTIME branch composed before reading anything out of it.
        onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.COMPLETED)).performScrollTo().assertExists()
        onNodeWithTag(DayViewTestTags.FocusClosureIntention).performScrollTo().assertTextContains("Écrire le rapport")
    }

    @Test
    fun crossingTheTermDropsADetourTollNoLongerOwed() = runComposeUiTest {
        val start = midWindowNow()
        val end = start + 1.minutes
        var state by mutableStateOf(focusUiState(now = start, pomodoroEnd = end))
        setContent { WideDayView(state = state, actions = noopDayViewActions()) }
        onNodeWithTag(DayViewTestTags.FocusStop).performScrollTo().performClick()
        onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.PROGRESSED)).performScrollTo().performClick()
        onNodeWithTag(DayViewTestTags.FocusExitDetourCategory).performScrollTo().assertExists()
        state = focusUiState(now = end + 1.minutes, pomodoroEnd = end)
        waitForIdle()
        // The sheet stays (overtime is where it lives) …
        onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.PROGRESSED)).performScrollTo().assertExists()
        // … but past the term no name is owed, so the capture folds away rather than
        // charging one — and its Confirm cannot become a control that does nothing.
        onNodeWithTag(DayViewTestTags.FocusExitDetourCategory).assertDoesNotExist()
        onNodeWithTag(DayViewTestTags.FocusExitDetourConfirm).assertDoesNotExist()
    }

    @Test
    fun theResumeRitualIsOfferedDuringOvertime() = runComposeUiTest {
        val now = midWindowNow()
        val snapshot = DayPreferencesSnapshot(
            focusIntention = "Écrire le rapport",
            pomodoroEnd = now - 1.minutes,
        )
        setContent {
            val c = remember { seededController(snapshot, now) }
            WideDayView(
                state = c.state,
                actions = controllerDayViewActions(c),
                reminders = FocusReminderUiState(
                    showDriftReminder = false,
                    dismissDriftReminder = {},
                    showResumeRitual = true,
                    dismissResumeRitual = {},
                ),
            )
        }
        // Prove the OVERTIME branch composed before reading anything into what it holds.
        onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.COMPLETED)).performScrollTo().assertExists()
        // Coming back during overtime meets the same resume point as before the term —
        // without it the latch would suppress every later drift nudge, invisibly.
        onNodeWithTag(DayViewTestTags.FocusResumeRitual).performScrollTo().assertExists()
    }

    @Test
    fun theBreakPanelKeepsTheFreeEntryAffordances() = runComposeUiTest {
        val now = midWindowNow()
        val snapshot = DayPreferencesSnapshot(
            focusIntention = "Écrire le rapport",
            breakStart = now - 1.minutes,
            pomodoroMinutes = 25,
        )
        lateinit var controller: DayViewController
        setContent {
            val c = remember { seededController(snapshot, now) }
            controller = c
            WideDayView(state = c.state, actions = controllerDayViewActions(c))
        }
        assertEquals(PomodoroStatus.BREAK, controller.state.pomodoroProgress.status)
        // A break runs up to an hour; the entry affordances cannot go missing for all of it.
        onNodeWithTag(DayViewTestTags.FocusDurationIncrease).performScrollTo().assertExists()
        onNodeWithTag(DayViewTestTags.FocusStart).performScrollTo().assertIsEnabled()
        onNodeWithTag(DayViewTestTags.FocusQuickStart).performScrollTo().performClick()
        // The 5-minute preset works from the break, and never rewrites the preference.
        assertEquals(5, controller.state.pomodoroSessionMinutes)
        assertEquals(25, controller.state.pomodoroMinutes)
    }

    @Test
    fun theBreakPanelCanStartWithAChosenDuration() = runComposeUiTest {
        val now = midWindowNow()
        val snapshot = DayPreferencesSnapshot(breakStart = now - 1.minutes, pomodoroMinutes = 25)
        lateinit var controller: DayViewController
        setContent {
            val c = remember { seededController(snapshot, now) }
            controller = c
            WideDayView(state = c.state, actions = controllerDayViewActions(c))
        }
        onNodeWithTag(DayViewTestTags.FocusDurationIncrease).performScrollTo().performClick()
        assertEquals(30, controller.state.pomodoroMinutes)
        onNodeWithTag(DayViewTestTags.FocusStart).performScrollTo().performClick()
        assertEquals(30, controller.state.pomodoroSessionMinutes)
    }

    @Test
    fun closureControlsAreAbsentDuringBreak() = runComposeUiTest {
        val now = midWindowNow()
        val snapshot = DayPreferencesSnapshot(
            focusIntention = "Écrire le rapport",
            breakStart = now - 1.minutes,
        )
        lateinit var controller: DayViewController
        setContent {
            val c = remember { seededController(snapshot, now) }
            controller = c
            WideDayView(state = c.state, actions = controllerDayViewActions(c))
        }
        assertEquals(PomodoroStatus.BREAK, controller.state.pomodoroProgress.status)
        // Prove the BREAK branch is actually composed (not just that these tags are
        // absent because the whole branch is missing): Relaunch is BREAK-only and renders.
        onNodeWithTag(DayViewTestTags.FocusRelaunch).performScrollTo().assertExists()
        // The session is already closed in BREAK: nothing is left to stop or to close.
        onNodeWithTag(DayViewTestTags.FocusStop).assertDoesNotExist()
        onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.COMPLETED)).assertDoesNotExist()
        onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.PROGRESSED)).assertDoesNotExist()
        onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.TO_RESUME)).assertDoesNotExist()
    }

    @Test
    fun closingPastTheTermOpensABreak() = runComposeUiTest {
        val now = midWindowNow()
        val snapshot = DayPreferencesSnapshot(
            focusIntention = "Écrire le rapport",
            pomodoroEnd = now - 1.minutes,
        )
        lateinit var controller: DayViewController
        setContent {
            val c = remember { seededController(snapshot, now) }
            controller = c
            WideDayView(state = c.state, actions = controllerDayViewActions(c))
        }
        // Past the term with no explicit closure is OVERTIME: time keeps counting until
        // a conscious closure, which is free of any toll and anchors the break.
        assertEquals(PomodoroStatus.OVERTIME, controller.state.pomodoroProgress.status)
        onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.TO_RESUME)).performScrollTo().performClick()
        assertEquals(PomodoroStatus.BREAK, controller.state.pomodoroProgress.status)
        assertEquals(FocusClosureOutcome.TO_RESUME, controller.state.lastFocusClosure)
    }

    @Test
    fun startIsEnabledWithABlankIntention() = runComposeUiTest {
        val now = midWindowNow()
        val snapshot = DayPreferencesSnapshot(focusIntention = "")
        lateinit var controller: DayViewController
        setContent {
            val c = remember { seededController(snapshot, now) }
            controller = c
            WideDayView(state = c.state, actions = controllerDayViewActions(c))
        }
        onNodeWithTag(DayViewTestTags.FocusEntry).performScrollTo().performClick()
        // Entering focus is free: naming the intention is no longer the toll on Start.
        onNodeWithTag(DayViewTestTags.FocusStart).performScrollTo().assertIsEnabled().performClick()
        assertTrue(controller.state.focusIsActive)
    }
}
