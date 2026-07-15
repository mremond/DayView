package fr.dayview.app

import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        onNodeWithTag(DayViewTestTags.FocusStop).performScrollTo().performClick()
        assertFalse(controller.state.focusIsActive)
    }

    @Test
    fun relaunchDuringBreakStartsNextFocusWithSameIntention() = runComposeUiTest {
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
        assertEquals(PomodoroStatus.BREAK, controller.state.pomodoroProgress.status)
        onNodeWithTag(DayViewTestTags.FocusRelaunch).performScrollTo().performClick()
        assertTrue(controller.state.focusIsActive)
        assertEquals("Écrire le rapport", controller.state.focusIntention)
    }

    @Test
    fun stopDuringBreakEndsSequenceWithoutClosure() = runComposeUiTest {
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
        assertEquals(PomodoroStatus.BREAK, controller.state.pomodoroProgress.status)
        onNodeWithTag(DayViewTestTags.FocusStop).performScrollTo().performClick()
        assertEquals(PomodoroStatus.IDLE, controller.state.pomodoroProgress.status)
        assertNull(controller.state.lastFocusClosure)
    }

    @Test
    fun startDisabledWhenIntentionBlank() = runComposeUiTest {
        val snapshot = DayPreferencesSnapshot(focusIntention = "")
        setContent {
            val state = remember { seededController(snapshot).state }
            WideDayView(state = state, actions = noopDayViewActions())
        }
        onNodeWithTag(DayViewTestTags.FocusEntry).performScrollTo().performClick()
        onNodeWithTag(DayViewTestTags.FocusStart).performScrollTo().assertIsNotEnabled()
    }
}
