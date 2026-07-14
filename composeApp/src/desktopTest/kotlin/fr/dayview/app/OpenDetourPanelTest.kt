package fr.dayview.app

import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalTestApi::class)
class OpenDetourPanelTest {
    @Test
    fun runningOpenDetourShowsPanelAndStopRecordsEpisode() = runComposeUiTest {
        val now = midWindowNow()
        val snapshot = DayPreferencesSnapshot(
            openDetourStart = now - 12.minutes,
            openDetourCategory = "Réunion",
        )
        lateinit var controller: DayViewController
        setContent {
            val c = remember { seededController(snapshot, now) }
            controller = c
            WideDayView(state = c.state, actions = controllerDayViewActions(c))
        }
        assertTrue(controller.state.openDetourRunning)
        onNodeWithTag(DayViewTestTags.OpenDetourStop).performClick()
        assertNull(controller.state.openDetourStart)
        assertFalse(controller.state.openDetourRunning)
        assertTrue(controller.state.detoursToday.isNotEmpty())
    }
}
