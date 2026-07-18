package fr.dayview.app

import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import fr.dayview.app.sync.SyncStatus
import java.util.Locale
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

    @Test
    fun minimumDesktopWidthKeepsPrimaryActionsVisible() = runComposeUiTest {
        val now = midWindowNow()
        val snapshot = DayPreferencesSnapshot(
            goalTitle = "Préparer la prochaine version",
            plannedObligationsDayKey = dayKeyOf(now),
            plannedObligations = listOf("Répondre au client", "Publier la version"),
        )
        setContent {
            val state = remember { seededController(snapshot, now).state }
            VisualViewport(width = 420.dp, height = 680.dp) {
                DayViewTheme {
                    DayViewScreen(
                        state = state,
                        actions = noopDayViewActions(openMiniWindow = {}),
                        reminders = noReminders(),
                    )
                }
            }
        }

        captureVisual("today-desktop-minimum-width-420x680")
        onNodeWithTag(DayViewTestTags.Countdown).assertIsDisplayed().assertInsideVisualViewport(this)
        onNodeWithTag(DayViewTestTags.TodayQuickActions).assertIsDisplayed().assertInsideVisualViewport(this)
        onNodeWithTag(DayViewTestTags.MiniWindow).assertIsDisplayed().assertInsideVisualViewport(this)
    }

    @Test
    fun lowDesktopHeightRemainsScrollableAtLargeText() = runComposeUiTest {
        val snapshot = DayPreferencesSnapshot(
            goalTitle = "Finaliser la présentation de la feuille de route",
            focusIntention = "Structurer les décisions importantes de la prochaine réunion",
        )
        setContent {
            val state = remember { seededController(snapshot).state }
            VisualViewport(width = 1000.dp, height = 520.dp, fontScale = 1.3f) {
                DayViewTheme {
                    DayViewScreen(
                        state = state,
                        actions = noopDayViewActions(openMiniWindow = {}),
                        reminders = noReminders(),
                    )
                }
            }
        }

        captureVisual("today-low-height-1000x520-font130-top")
        onNodeWithTag(DayViewTestTags.TodayQuickActions).assertIsDisplayed().assertInsideVisualViewport(this)
        onNodeWithTag(DayViewTestTags.FocusEntry).performScrollTo().assertIsDisplayed().assertInsideVisualViewport(this)
        captureVisual("today-low-height-1000x520-font130-scrolled")
    }

    @Test
    fun compactMainWindowBringsActiveFocusIntoView() = runComposeUiTest {
        val now = midWindowNow()
        val snapshot = DayPreferencesSnapshot(
            focusIntention = "Finaliser la présentation",
            pomodoroEnd = now + 25.minutes,
        )
        setContent {
            val state = remember { seededController(snapshot, now).state }
            VisualViewport(width = 420.dp, height = 680.dp) {
                DayViewTheme {
                    DayViewScreen(
                        state = state,
                        actions = noopDayViewActions(openMiniWindow = {}),
                        reminders = noReminders(),
                    )
                }
            }
        }

        onNodeWithTag(DayViewTestTags.FocusStop).assertIsDisplayed().assertInsideVisualViewport(this)
        captureVisual("today-compact-active-focus-auto-scrolled")
    }

    @Test
    fun androidCompactViewportKeepsTouchActionsVisibleAtOneHundredThirtyPercent() = runComposeUiTest {
        val now = midWindowNow()
        val snapshot = DayPreferencesSnapshot(
            goalTitle = "Préparer la prochaine version Android",
            plannedObligationsDayKey = dayKeyOf(now),
            plannedObligations = listOf("Vérifier les notifications", "Tester hors connexion"),
        )
        setContent {
            val state = remember { seededController(snapshot, now).state }
            VisualViewport(width = 360.dp, height = 640.dp, fontScale = 1.3f) {
                DayViewTheme {
                    DayViewScreen(
                        state = state,
                        actions = noopDayViewActions(),
                        reminders = noReminders(),
                    )
                }
            }
        }

        captureVisual("today-android-compact-360x640-font130")
        onNodeWithTag(DayViewTestTags.HistoryIcon).assertIsDisplayed().assertInsideVisualViewport(this)
        onNodeWithTag(DayViewTestTags.SettingsIcon).assertIsDisplayed().assertInsideVisualViewport(this)
        onNodeWithTag(DayViewTestTags.AddDetourQuickAction).assertIsDisplayed().assertInsideVisualViewport(this)
        onNodeWithTag(DayViewTestTags.OpenObligationsQuickAction).assertIsDisplayed().assertInsideVisualViewport(this)
        onNodeWithTag(DayViewTestTags.FocusEntry).assertIsDisplayed().assertInsideVisualViewport(this)
    }

    @Test
    fun frenchLongCopyFitsAtOneHundredFiftyPercent() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRENCH)
            runComposeUiTest {
                val now = midWindowNow()
                val snapshot = DayPreferencesSnapshot(
                    goalTitle = "Préparer et faire valider la présentation stratégique du prochain trimestre",
                    focusIntention = "Relire attentivement toutes les hypothèses avec les responsables concernés",
                    plannedObligationsDayKey = dayKeyOf(now),
                    plannedObligations = listOf(
                        "Confirmer le rendez-vous avec toute l’équipe",
                        "Envoyer le compte rendu détaillé au partenaire",
                        "Préparer les documents administratifs nécessaires",
                    ),
                )
                setContent {
                    val state = remember { seededController(snapshot, now).state }
                    VisualViewport(width = 420.dp, height = 680.dp, fontScale = 1.5f) {
                        DayViewTheme {
                            DayViewScreen(
                                state = state,
                                actions = noopDayViewActions(openMiniWindow = {}),
                                reminders = noReminders(),
                            )
                        }
                    }
                }

                captureVisual("today-french-long-copy-420x680-font150")
                onNodeWithTag(DayViewTestTags.AddDetourQuickAction).assertIsDisplayed()
                onNodeWithTag(DayViewTestTags.OpenObligationsQuickAction).assertIsDisplayed()
                onNodeWithTag(DayViewTestTags.TodayQuickActions).assertInsideVisualViewport(this)
                onNodeWithTag(DayViewTestTags.SettingsIcon).assertInsideVisualViewport(this)
                onNodeWithTag(DayViewTestTags.FocusEntry).assertIsDisplayed().assertInsideVisualViewport(this)
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }
}
