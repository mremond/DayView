package fr.dayview.app

import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class FocusStartTest {
    private fun t(ms: Long): Instant = Instant.fromEpochMilliseconds(ms)

    @Test
    fun addsDurationInMinutesToNow() {
        assertEquals(t(1_000L + 25 * 60_000L), focusStartEnd(t(1_000L), 25))
    }

    @Test
    fun coercesDurationBelowFiveMinutesUpToFive() {
        assertEquals(t(5 * 60_000L), focusStartEnd(t(0L), 1))
    }

    @Test
    fun coercesDurationAboveOneEightyMinutesDownToOneEighty() {
        assertEquals(t(180 * 60_000L), focusStartEnd(t(0L), 500))
    }

    @Test
    fun sessionMinutesPassesThroughWithinRange() {
        assertEquals(15, focusStartSessionMinutes(15))
    }

    @Test
    fun sessionMinutesCoercesBelowFiveUpToFive() {
        assertEquals(5, focusStartSessionMinutes(1))
    }

    @Test
    fun sessionMinutesCoercesAboveOneEightyDownToOneEighty() {
        assertEquals(180, focusStartSessionMinutes(500))
    }

    // --- Entering focus is free: the intention field never gates the Start button.

    @Test
    fun startIsEnabledWithBlankIntention() = runComposeUiTest {
        val snapshot = DayPreferencesSnapshot(focusIntention = "")
        setContent {
            val c = remember { seededController(snapshot) }
            WideDayView(state = c.state, actions = controllerDayViewActions(c))
        }
        onNodeWithTag(DayViewTestTags.FocusEntry).performScrollTo().performClick()
        onNodeWithTag(DayViewTestTags.FocusStart).performScrollTo().assertIsEnabled()
    }

    @Test
    fun quickStartLaunchesFiveMinuteSession() = runComposeUiTest {
        // pomodoroMinutes defaults to 25, so a 5-minute result proves the quick-start
        // preset is used rather than the preferred duration falling through unchanged.
        val snapshot = DayPreferencesSnapshot(focusIntention = "", pomodoroMinutes = 25)
        lateinit var controller: DayViewController
        setContent {
            val c = remember { seededController(snapshot) }
            controller = c
            WideDayView(state = c.state, actions = controllerDayViewActions(c))
        }
        onNodeWithTag(DayViewTestTags.FocusEntry).performScrollTo().performClick()
        onNodeWithTag(DayViewTestTags.FocusQuickStart).performScrollTo().performClick()
        assertEquals(5, controller.state.pomodoroSessionMinutes)
    }

    // --- The mini window's direct-persist start path mirrors both of
    // DayViewController.startPomodoro's preconditions, so it can never write a snapshot
    // where a session and an open detour are both active.

    @Test
    fun snapshotWritesSessionFieldsWhenNothingBlocksTheStart() {
        val snapshot = DayPreferencesSnapshot(
            focusIntention = "old",
            breakStart = t(1_000L),
            pomodoroSessionMinutes = 99,
        )
        val next = focusStartSnapshot(snapshot, now = t(0L), intention = "  Ship the thing  ", durationMinutes = 500)
        assertEquals(
            snapshot.copy(
                focusIntention = "Ship the thing",
                pomodoroMinutes = 500,
                pomodoroEnd = t(180 * 60_000L),
                pomodoroSessionMinutes = 180,
                breakStart = null,
            ),
            next,
        )
    }

    @Test
    fun snapshotIsNullWhenAnOpenDetourIsRunning() {
        val snapshot = DayPreferencesSnapshot(
            focusIntention = "",
            openDetourStart = t(0L),
            openDetourCategory = "Mail",
        )
        assertNull(focusStartSnapshot(snapshot, now = t(60_000L), intention = "Ship", durationMinutes = 25))
    }

    @Test
    fun snapshotIsNullWhenASessionIsAlreadyRunning() {
        val snapshot = DayPreferencesSnapshot(focusIntention = "", pomodoroEnd = t(600_000L))
        assertNull(focusStartSnapshot(snapshot, now = t(0L), intention = "Ship", durationMinutes = 25))
    }
}
