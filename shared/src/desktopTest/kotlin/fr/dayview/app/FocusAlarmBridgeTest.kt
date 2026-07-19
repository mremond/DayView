package fr.dayview.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for the `onFocusAlarmChange` bridge itself — the three call sites inside
 * [DayViewApp] that fire it after `startPomodoro`/`quickStartPomodoro`/a refused
 * `closePomodoro`. Each must pass `controller.state.sessionMinutesEffective`, the controller's
 * synchronous in-memory value, never a re-read of the (possibly stale, possibly not-yet-landed)
 * persisted snapshot — that re-read is exactly what the Android side used to do wrong.
 */
@OptIn(ExperimentalTestApi::class)
class FocusAlarmBridgeTest {
    @Test
    fun startPomodoroPassesTheFreshSessionMinutesNotAStalePersistedOne() = runComposeUiTest {
        // A previous 50-minute session left pomodoroSessionMinutes = 50 sitting in the
        // snapshot; the user has since dialled the duration down to 15 and starts. The stale
        // 50 must not win — DayViewController.startPomodoro sets pomodoroSessionMinutes = 15
        // in memory synchronously, before onFocusAlarmChange fires.
        val snapshot = DayPreferencesSnapshot(
            focusIntention = "Écrire le rapport",
            pomodoroMinutes = 15,
            pomodoroSessionMinutes = 50,
        )
        var capturedSessionMinutes: Int? = null
        setContent {
            Box(Modifier.requiredSize(1000.dp, 720.dp)) {
                DayViewApp(
                    preferences = InMemoryDayPreferences(snapshot),
                    onFocusAlarmChange = { _, _, sessionMinutes -> capturedSessionMinutes = sessionMinutes },
                )
            }
        }
        onNodeWithTag(DayViewTestTags.FocusEntry).performScrollTo().performClick()
        onNodeWithTag(DayViewTestTags.FocusStart).performScrollTo().performClick()
        assertEquals(15, capturedSessionMinutes)
    }

    @Test
    fun quickStartPassesFiveMinutesNotTheStandingDuration() = runComposeUiTest {
        // quickStartPomodoro starts a fixed 5-minute session without ever writing
        // pomodoroMinutes; the bridge must still report 5, not the 25-minute standing
        // preference the DataStore-re-read approach used to fall back to.
        val snapshot = DayPreferencesSnapshot(focusIntention = "Écrire le rapport", pomodoroMinutes = 25)
        var capturedSessionMinutes: Int? = null
        setContent {
            Box(Modifier.requiredSize(1000.dp, 720.dp)) {
                DayViewApp(
                    preferences = InMemoryDayPreferences(snapshot),
                    onFocusAlarmChange = { _, _, sessionMinutes -> capturedSessionMinutes = sessionMinutes },
                )
            }
        }
        onNodeWithTag(DayViewTestTags.FocusEntry).performScrollTo().performClick()
        onNodeWithTag(DayViewTestTags.FocusQuickStart).performScrollTo().performClick()
        assertEquals(5, capturedSessionMinutes)
    }
}
