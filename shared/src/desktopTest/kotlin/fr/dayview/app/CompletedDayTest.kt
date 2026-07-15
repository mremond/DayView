package fr.dayview.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalTestApi::class)
class CompletedDayTest {
    private fun finishedProgress(): DayProgress = calculateDayProgress(
        now = afterWindowNow(),
        startMinutesOfDay = 8 * 60,
        endMinutesOfDay = 18 * 60,
    )

    @Test
    fun rendersFocusRecapWhenFinishedWithFocusTime() = runComposeUiTest {
        setContent {
            DayViewTheme {
                CountdownCircle(
                    progress = finishedProgress(),
                    showSeconds = false,
                    focusedToday = 90.minutes,
                )
            }
        }
        onNodeWithTag(DayViewTestTags.FocusRecap).assertExists()
    }

    @Test
    fun hidesFocusRecapWhenFinishedWithoutFocusTime() = runComposeUiTest {
        setContent {
            DayViewTheme {
                CountdownCircle(
                    progress = finishedProgress(),
                    showSeconds = false,
                    focusedToday = kotlin.time.Duration.ZERO,
                )
            }
        }
        onNodeWithTag(DayViewTestTags.FocusRecap).assertDoesNotExist()
    }

    @Test
    fun rendersEngagedRecapWhenFinishedWithSessionTime() = runComposeUiTest {
        setContent {
            DayViewTheme {
                CountdownCircle(
                    progress = finishedProgress(),
                    showSeconds = false,
                    focusedToday = 90.minutes,
                    sessionFocusedToday = 120.minutes,
                )
            }
        }
        onNodeWithTag(DayViewTestTags.EngagedRecap).assertExists()
    }

    @Test
    fun hidesEngagedRecapWhenFinishedWithoutSessionTime() = runComposeUiTest {
        setContent {
            DayViewTheme {
                CountdownCircle(
                    progress = finishedProgress(),
                    showSeconds = false,
                    focusedToday = 90.minutes,
                    sessionFocusedToday = kotlin.time.Duration.ZERO,
                )
            }
        }
        onNodeWithTag(DayViewTestTags.EngagedRecap).assertDoesNotExist()
    }

    @Test
    fun rendersEngagedRecapWhenFinishedWithOnlySessionTime() = runComposeUiTest {
        setContent {
            DayViewTheme {
                CountdownCircle(
                    progress = finishedProgress(),
                    showSeconds = false,
                    focusedToday = kotlin.time.Duration.ZERO,
                    sessionFocusedToday = 120.minutes,
                )
            }
        }
        onNodeWithTag(DayViewTestTags.EngagedRecap).assertExists()
        onNodeWithTag(DayViewTestTags.FocusRecap).assertDoesNotExist()
    }

    @Test
    fun rendersCleanSessionsWhenFinished() = runComposeUiTest {
        setContent {
            DayViewTheme {
                CountdownCircle(
                    progress = finishedProgress(),
                    showSeconds = false,
                    cleanSessionsToday = 3,
                    streakDays = 5,
                )
            }
        }
        onNodeWithTag(DayViewTestTags.CleanSessions).assertExists()
    }
}
