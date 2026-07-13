package fr.dayview.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
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
                recentMotifs = emptyList(),
                now = midWindowNow(),
                onConfirm = { motif, duration, start -> captured = Triple(motif, duration, start) },
                onForget = {},
                onDismiss = {},
            )
        }
        onNodeWithTag(DayViewTestTags.DetourMotifField).performTextInput("café")
        onNodeWithTag(DayViewTestTags.DetourConfirm).performClick()

        val (motif, duration, start) = captured!!
        assertEquals("café", motif)
        assertEquals(15, duration)
        assertNull(start) // untouched start stays "ends now", handled by addDetour
    }

    @Test
    fun adjustingPinsAnExplicitStart() = runComposeUiTest {
        var captured: Triple<String, Int, Int?>? = null
        setContent {
            DetourCaptureContent(
                recentMotifs = emptyList(),
                now = midWindowNow(),
                onConfirm = { motif, duration, start -> captured = Triple(motif, duration, start) },
                onForget = {},
                onDismiss = {},
            )
        }
        onNodeWithTag(DayViewTestTags.DetourMotifField).performTextInput("café")
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
                recentMotifs = emptyList(),
                now = midWindowNow(),
                onConfirm = { motif, duration, start -> captured = Triple(motif, duration, start) },
                onForget = {},
                onDismiss = {},
            )
        }
        onNodeWithTag(DayViewTestTags.DetourMotifField).performTextInput("série")
        onNodeWithTag(DayViewTestTags.DetourLongToggle).performClick()
        onNodeWithTag(DayViewTestTags.detourDurationChip(180)).performClick()
        onNodeWithTag(DayViewTestTags.DetourConfirm).performClick()

        val (motif, duration, start) = captured!!
        assertEquals("série", motif)
        assertEquals(180, duration) // 3 h reached from quick capture
        assertNull(start) // start untouched → "ends now"
    }

    @Test
    fun typingAStartTimePinsIt() = runComposeUiTest {
        var captured: Triple<String, Int, Int?>? = null
        setContent {
            DetourCaptureContent(
                recentMotifs = emptyList(),
                now = midWindowNow(),
                onConfirm = { motif, duration, start -> captured = Triple(motif, duration, start) },
                onForget = {},
                onDismiss = {},
            )
        }
        onNodeWithTag(DayViewTestTags.DetourMotifField).performTextInput("café")
        onNodeWithTag(DayViewTestTags.DetourStartAdjust).performClick()
        onNodeWithTag(DayViewTestTags.DetourStartValue).performClick()
        onNodeWithTag(DayViewTestTags.DetourStartField).performTextClearance()
        onNodeWithTag(DayViewTestTags.DetourStartField).performTextInput("9h05")
        onNodeWithTag(DayViewTestTags.DetourStartField).performImeAction()
        onNodeWithTag(DayViewTestTags.DetourConfirm).performClick()

        assertEquals(9 * 60 + 5, captured!!.third)
    }

    @Test
    fun invalidTypedStartRevertsToPreviousValue() = runComposeUiTest {
        var captured: Triple<String, Int, Int?>? = null
        setContent {
            DetourCaptureContent(
                recentMotifs = emptyList(),
                now = midWindowNow(),
                onConfirm = { motif, duration, start -> captured = Triple(motif, duration, start) },
                onForget = {},
                onDismiss = {},
            )
        }
        onNodeWithTag(DayViewTestTags.DetourMotifField).performTextInput("café")
        onNodeWithTag(DayViewTestTags.DetourStartAdjust).performClick()
        onNodeWithTag(DayViewTestTags.DetourStartValue).performClick()
        onNodeWithTag(DayViewTestTags.DetourStartField).performTextClearance()
        onNodeWithTag(DayViewTestTags.DetourStartField).performTextInput("99:99")
        onNodeWithTag(DayViewTestTags.DetourStartField).performImeAction()
        // The field closes without committing; the start stays unpinned.
        onNodeWithTag(DayViewTestTags.DetourStartValue).assertExists()
        onNodeWithTag(DayViewTestTags.DetourConfirm).performClick()

        assertNull(captured!!.third)
    }

    @Test
    fun nudgingFromTypedMisalignedStartSnapsToMultipleOfFive() = runComposeUiTest {
        var captured: Triple<String, Int, Int?>? = null
        setContent {
            DetourCaptureContent(
                recentMotifs = emptyList(),
                now = midWindowNow(),
                onConfirm = { motif, duration, start -> captured = Triple(motif, duration, start) },
                onForget = {},
                onDismiss = {},
            )
        }
        onNodeWithTag(DayViewTestTags.DetourMotifField).performTextInput("café")
        onNodeWithTag(DayViewTestTags.DetourStartAdjust).performClick()
        onNodeWithTag(DayViewTestTags.DetourStartValue).performClick()
        onNodeWithTag(DayViewTestTags.DetourStartField).performTextClearance()
        onNodeWithTag(DayViewTestTags.DetourStartField).performTextInput("9h07")
        onNodeWithTag(DayViewTestTags.DetourStartField).performImeAction()
        onNodeWithTag(DayViewTestTags.DetourStartIncrease).performClick()
        onNodeWithTag(DayViewTestTags.DetourConfirm).performClick()

        // 9:07 + snaps up to 9:10, not 9:12.
        assertEquals(9 * 60 + 10, captured!!.third)
    }
}
