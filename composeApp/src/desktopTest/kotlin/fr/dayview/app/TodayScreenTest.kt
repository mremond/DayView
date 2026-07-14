package fr.dayview.app

import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import fr.dayview.app.sync.SyncStatus
import kotlin.test.Test
import kotlin.test.assertTrue
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
        onNodeWithText("Livrer la v2").assertExists()
        onNodeWithTag(DayViewTestTags.FocusEntry).assertExists()
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

    @Test
    fun miniWindowButtonInvokesCallback() = runComposeUiTest {
        var miniOpened = false
        setContent {
            val state = remember { seededController(DayPreferencesSnapshot()).state }
            WideDayView(
                state = state,
                actions = noopDayViewActions(openMiniWindow = { miniOpened = true }),
            )
        }
        onNodeWithTag(DayViewTestTags.MiniWindow).assertExists()
        onNodeWithTag(DayViewTestTags.MiniWindow).performClick()
        assertTrue(miniOpened)
    }

    @Test
    fun showsCalendarNoticeWhenNetTimeEnabledButPermissionMissing() = runComposeUiTest {
        var netTimeOpened = false
        val snapshot = DayPreferencesSnapshot(netTimeSettings = NetTimeSettings(enabled = true))
        setContent {
            // netCalendarPermission defaults to false, so an enabled Net Time surfaces the notice.
            val state = remember { seededController(snapshot).state }
            WideDayView(
                state = state,
                actions = noopDayViewActions(openNetTimeSettings = { netTimeOpened = true }),
            )
        }
        onNodeWithTag(DayViewTestTags.CalendarNotice).assertExists()
        onNodeWithTag(DayViewTestTags.CalendarNotice).performClick()
        assertTrue(netTimeOpened)
    }

    @Test
    fun hidesCalendarNoticeWhenNetTimeDisabled() = runComposeUiTest {
        setContent {
            val state = remember { seededController(DayPreferencesSnapshot()).state }
            WideDayView(state = state, actions = noopDayViewActions())
        }
        onNodeWithTag(DayViewTestTags.CalendarNotice).assertDoesNotExist()
    }

    @Test
    fun showsSyncNoticeOnFailureAndRoutesToSyncSettings() = runComposeUiTest {
        var syncOpened = false
        setContent {
            val state = remember { seededController(DayPreferencesSnapshot()).state }
            WideDayView(
                state = state,
                actions = noopDayViewActions(openSyncSettings = { syncOpened = true }),
                syncStatus = SyncStatus.Failed,
            )
        }
        onNodeWithTag(DayViewTestTags.SyncNotice).assertExists()
        onNodeWithTag(DayViewTestTags.SyncNotice).performClick()
        assertTrue(syncOpened)
    }

    @Test
    fun hidesSyncNoticeWhenHealthy() = runComposeUiTest {
        setContent {
            val state = remember { seededController(DayPreferencesSnapshot()).state }
            WideDayView(state = state, actions = noopDayViewActions(), syncStatus = SyncStatus.Ok)
        }
        onNodeWithTag(DayViewTestTags.SyncNotice).assertDoesNotExist()
    }

    @Test
    fun rendersCleanSessionsLineWhenPresent() = runComposeUiTest {
        val now = midWindowNow()
        val dayKey = dayKeyOf(now)
        val snapshot = DayPreferencesSnapshot(
            cleanSessions = CleanSessionLedger(dayKey = dayKey, cleanToday = 3, streakDays = 5, streakLastDayKey = dayKey),
        )
        setContent {
            val state = remember { seededController(snapshot, now).state }
            WideDayView(state = state, actions = noopDayViewActions())
        }
        onNodeWithTag(DayViewTestTags.CleanSessions).assertExists()
    }

    @Test
    fun rendersLiveStreakBeforeFirstSessionOfDay() = runComposeUiTest {
        val now = midWindowNow()
        val dayKey = dayKeyOf(now)
        val snapshot = DayPreferencesSnapshot(
            cleanSessions = CleanSessionLedger(
                dayKey = dayKey,
                cleanToday = 0,
                streakDays = 5,
                streakLastDayKey = dayKey - 1,
            ),
        )
        setContent {
            val state = remember { seededController(snapshot, now).state }
            WideDayView(state = state, actions = noopDayViewActions())
        }
        onNodeWithTag(DayViewTestTags.CleanSessions).assertExists()
    }
}
