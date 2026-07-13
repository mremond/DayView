package fr.dayview.app

import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
        onNodeWithTag(DayViewTestTags.FocusRelaunch).performClick()
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
        onNodeWithTag(DayViewTestTags.FocusStop).performClick()
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
        onNodeWithTag(DayViewTestTags.FocusStart).assertIsNotEnabled()
    }
}
