@file:OptIn(ExperimentalTestApi::class)

package fr.dayview.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class FocusSessionPopupTest {
    @Test
    fun showsSessionReadoutForARecord() = runComposeUiTest {
        val record = FocusSessionRecord(
            Instant.fromEpochMilliseconds(0),
            Instant.fromEpochMilliseconds(25 * 60_000),
            "write the plan",
            FocusClosureOutcome.COMPLETED,
        )
        setContent {
            DayViewTheme {
                FocusSessionReadoutHost(
                    record = record,
                    engaged = 25.minutes,
                    deepFocus = 18.minutes,
                    uses24Hour = true,
                )
            }
        }
        onNodeWithTag(DayViewTestTags.FocusSessionPopup).assertExists()
    }

    @Test
    fun rendersForABlankIntentionRecord() = runComposeUiTest {
        val record = FocusSessionRecord(
            Instant.fromEpochMilliseconds(0),
            Instant.fromEpochMilliseconds(25 * 60_000),
            "   ",
            FocusClosureOutcome.COMPLETED,
        )
        setContent {
            DayViewTheme {
                FocusSessionReadoutHost(
                    record = record,
                    engaged = 25.minutes,
                    deepFocus = 10.minutes,
                    uses24Hour = true,
                )
            }
        }
        onNodeWithTag(DayViewTestTags.FocusSessionPopup).assertExists()
    }

    @Test
    fun hidesDeepFocusLineWhenZero() = runComposeUiTest {
        val record = FocusSessionRecord(
            Instant.fromEpochMilliseconds(0),
            Instant.fromEpochMilliseconds(25 * 60_000),
            "write the plan",
            outcome = null,
        )
        setContent {
            DayViewTheme {
                FocusSessionReadoutHost(
                    record = record,
                    engaged = 25.minutes,
                    deepFocus = Duration.ZERO,
                    uses24Hour = true,
                )
            }
        }
        onNodeWithTag(DayViewTestTags.FocusSessionPopup).assertExists()
        onNodeWithTag(DayViewTestTags.FocusSessionDeepFocus).assertDoesNotExist()
    }

    @Test
    fun showsDeepFocusLineWhenNonZero() = runComposeUiTest {
        val record = FocusSessionRecord(
            Instant.fromEpochMilliseconds(0),
            Instant.fromEpochMilliseconds(25 * 60_000),
            "write the plan",
            FocusClosureOutcome.PROGRESSED,
        )
        setContent {
            DayViewTheme {
                FocusSessionReadoutHost(
                    record = record,
                    engaged = 25.minutes,
                    deepFocus = 18.minutes,
                    uses24Hour = true,
                )
            }
        }
        onNodeWithTag(DayViewTestTags.FocusSessionDeepFocus).assertExists()
    }
}
