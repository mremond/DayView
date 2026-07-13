package fr.dayview.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class DetourCaptureTest {
    @Test
    fun confirmsWithNullStartWhenNotAdjusted() = runComposeUiTest {
        var captured: Triple<String, Int, Int?>? = null
        setContent {
            // midWindowNow() is 13:00 local; the default duration is 15 minutes.
            DetourCaptureContent(
                recentCategories = emptyList(),
                now = midWindowNow(),
                onConfirm = { category, duration, start -> captured = Triple(category, duration, start) },
                onForget = {},
                onDismiss = {},
            )
        }
        onNodeWithTag(DayViewTestTags.DetourCategoryField).performTextInput("café")
        onNodeWithTag(DayViewTestTags.DetourConfirm).performClick()

        val (category, duration, start) = captured!!
        assertEquals("café", category)
        assertEquals(15, duration)
        assertNull(start) // untouched start stays "ends now", handled by addDetour
    }

    @Test
    fun adjustingPinsAnExplicitStart() = runComposeUiTest {
        var captured: Triple<String, Int, Int?>? = null
        setContent {
            DetourCaptureContent(
                recentCategories = emptyList(),
                now = midWindowNow(),
                onConfirm = { category, duration, start -> captured = Triple(category, duration, start) },
                onForget = {},
                onDismiss = {},
            )
        }
        onNodeWithTag(DayViewTestTags.DetourCategoryField).performTextInput("café")
        onNodeWithTag(DayViewTestTags.DetourStartAdjust).performClick()
        onNodeWithTag(DayViewTestTags.DetourStartIncrease).performClick()
        onNodeWithTag(DayViewTestTags.DetourConfirm).performClick()

        // Default start is 13:00 − 15 min = 12:45 (765); one +5 nudge pins it at 12:50 (770).
        assertEquals(12 * 60 + 50, captured!!.third)
    }

    @Test
    fun longerRevealsAndSelectsMultiHourDurations() = runComposeUiTest {
        var captured: Triple<String, Int, Int?>? = null
        setContent {
            DetourCaptureContent(
                recentCategories = emptyList(),
                now = midWindowNow(),
                onConfirm = { category, duration, start -> captured = Triple(category, duration, start) },
                onForget = {},
                onDismiss = {},
            )
        }
        onNodeWithTag(DayViewTestTags.DetourCategoryField).performTextInput("série")
        onNodeWithTag(DayViewTestTags.DetourLongToggle).performClick()
        onNodeWithTag(DayViewTestTags.detourDurationChip(180)).performClick()
        onNodeWithTag(DayViewTestTags.DetourConfirm).performClick()

        val (category, duration, start) = captured!!
        assertEquals("série", category)
        assertEquals(180, duration) // 3 h reached from quick capture
        assertNull(start) // start untouched → "ends now"
    }
}
