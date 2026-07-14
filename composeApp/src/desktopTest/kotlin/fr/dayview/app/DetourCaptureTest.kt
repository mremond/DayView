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
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalTestApi::class)
class DetourCaptureTest {
    @Test
    fun confirmsWithNullStartWhenNotAdjusted() = runComposeUiTest {
        var category: String? = null
        var description: String? = null
        var duration: Int? = null
        var start: Int? = null
        setContent {
            // midWindowNow() is 13:00 local; the default duration is 15 minutes.
            DetourCaptureContent(
                recentCategories = emptyList(),
                now = midWindowNow(),
                onConfirm = { c, d, dur, s ->
                    category = c
                    description = d
                    duration = dur
                    start = s
                },
                onForget = {},
                onDismiss = {},
            )
        }
        onNodeWithTag(DayViewTestTags.DetourCategoryField).performTextInput("café")
        onNodeWithTag(DayViewTestTags.DetourConfirm).performClick()

        assertEquals("café", category)
        assertEquals("", description)
        assertEquals(15, duration)
        assertNull(start) // untouched start stays "ends now", handled by addDetour
    }

    @Test
    fun adjustingPinsAnExplicitStart() = runComposeUiTest {
        var category: String? = null
        var description: String? = null
        var duration: Int? = null
        var start: Int? = null
        setContent {
            DetourCaptureContent(
                recentCategories = emptyList(),
                now = midWindowNow(),
                onConfirm = { c, d, dur, s ->
                    category = c
                    description = d
                    duration = dur
                    start = s
                },
                onForget = {},
                onDismiss = {},
            )
        }
        onNodeWithTag(DayViewTestTags.DetourCategoryField).performTextInput("café")
        onNodeWithTag(DayViewTestTags.DetourStartAdjust).performClick()
        onNodeWithTag(DayViewTestTags.DetourStartIncrease).performClick()
        onNodeWithTag(DayViewTestTags.DetourConfirm).performClick()

        // Default start is 13:00 − 15 min = 12:45 (765); one +5 nudge pins it at 12:50 (770).
        assertEquals(12 * 60 + 50, start)
    }

    @Test
    fun durationFieldAcceptsAnyMinuteCount() = runComposeUiTest {
        var category: String? = null
        var description: String? = null
        var duration: Int? = null
        var start: Int? = null
        setContent {
            DetourCaptureContent(
                recentCategories = emptyList(),
                now = midWindowNow(),
                onConfirm = { c, d, dur, s ->
                    category = c
                    description = d
                    duration = dur
                    start = s
                },
                onForget = {},
                onDismiss = {},
            )
        }
        onNodeWithTag(DayViewTestTags.DetourCategoryField).performTextInput("série")
        onNodeWithTag(DayViewTestTags.DetourDurationValue).performClick()
        onNodeWithTag(DayViewTestTags.DetourDurationField).performTextClearance()
        onNodeWithTag(DayViewTestTags.DetourDurationField).performTextInput("180")
        onNodeWithTag(DayViewTestTags.DetourDurationField).performImeAction()
        onNodeWithTag(DayViewTestTags.DetourConfirm).performClick()

        assertEquals("série", category)
        assertEquals("", description)
        assertEquals(180, duration) // 3 h typed from quick capture
        assertNull(start) // start untouched → "ends now"
    }

    @Test
    fun confirmsWithDescriptionWhenEntered() = runComposeUiTest {
        var category: String? = null
        var description: String? = null
        var duration: Int? = null
        var start: Int? = null
        setContent {
            DetourCaptureContent(
                recentCategories = emptyList(),
                now = midWindowNow(),
                onConfirm = { c, d, dur, s ->
                    category = c
                    description = d
                    duration = dur
                    start = s
                },
                onForget = {},
                onDismiss = {},
            )
        }
        onNodeWithTag(DayViewTestTags.DetourCategoryField).performTextInput("café")
        onNodeWithTag(DayViewTestTags.DetourDescriptionField).performTextInput("pause clope")
        onNodeWithTag(DayViewTestTags.DetourConfirm).performClick()

        assertEquals("café", category)
        assertEquals("pause clope", description)
        assertEquals(15, duration)
        assertNull(start)
    }

    @Test
    fun typingAStartTimePinsIt() = runComposeUiTest {
        var start: Int? = null
        setContent {
            DetourCaptureContent(
                recentCategories = emptyList(),
                now = midWindowNow(),
                onConfirm = { _, _, _, s -> start = s },
                onForget = {},
                onDismiss = {},
            )
        }
        onNodeWithTag(DayViewTestTags.DetourCategoryField).performTextInput("café")
        onNodeWithTag(DayViewTestTags.DetourStartAdjust).performClick()
        onNodeWithTag(DayViewTestTags.DetourStartValue).performClick()
        onNodeWithTag(DayViewTestTags.DetourStartField).performTextClearance()
        onNodeWithTag(DayViewTestTags.DetourStartField).performTextInput("9h05")
        onNodeWithTag(DayViewTestTags.DetourStartField).performImeAction()
        onNodeWithTag(DayViewTestTags.DetourConfirm).performClick()

        assertEquals(9 * 60 + 5, start)
    }

    @Test
    fun invalidTypedStartRevertsToPreviousValue() = runComposeUiTest {
        var start: Int? = null
        setContent {
            DetourCaptureContent(
                recentCategories = emptyList(),
                now = midWindowNow(),
                onConfirm = { _, _, _, s -> start = s },
                onForget = {},
                onDismiss = {},
            )
        }
        onNodeWithTag(DayViewTestTags.DetourCategoryField).performTextInput("café")
        onNodeWithTag(DayViewTestTags.DetourStartAdjust).performClick()
        onNodeWithTag(DayViewTestTags.DetourStartValue).performClick()
        onNodeWithTag(DayViewTestTags.DetourStartField).performTextClearance()
        onNodeWithTag(DayViewTestTags.DetourStartField).performTextInput("99:99")
        onNodeWithTag(DayViewTestTags.DetourStartField).performImeAction()
        // The field closes without committing; the start stays unpinned.
        onNodeWithTag(DayViewTestTags.DetourStartValue).assertExists()
        onNodeWithTag(DayViewTestTags.DetourConfirm).performClick()

        assertNull(start)
    }

    @Test
    fun nudgingFromTypedMisalignedStartSnapsToMultipleOfFive() = runComposeUiTest {
        var start: Int? = null
        setContent {
            DetourCaptureContent(
                recentCategories = emptyList(),
                now = midWindowNow(),
                onConfirm = { _, _, _, s -> start = s },
                onForget = {},
                onDismiss = {},
            )
        }
        onNodeWithTag(DayViewTestTags.DetourCategoryField).performTextInput("café")
        onNodeWithTag(DayViewTestTags.DetourStartAdjust).performClick()
        onNodeWithTag(DayViewTestTags.DetourStartValue).performClick()
        onNodeWithTag(DayViewTestTags.DetourStartField).performTextClearance()
        onNodeWithTag(DayViewTestTags.DetourStartField).performTextInput("9h07")
        onNodeWithTag(DayViewTestTags.DetourStartField).performImeAction()
        onNodeWithTag(DayViewTestTags.DetourStartIncrease).performClick()
        onNodeWithTag(DayViewTestTags.DetourConfirm).performClick()

        // 9:07 + snaps up to 9:10, not 9:12.
        assertEquals(9 * 60 + 10, start)
    }

    @Test
    fun openingTheStartFieldWithoutTypingDoesNotPinTheStart() = runComposeUiTest {
        var start: Int? = null
        setContent {
            DetourCaptureContent(
                recentCategories = emptyList(),
                now = midWindowNow(),
                onConfirm = { _, _, _, s -> start = s },
                onForget = {},
                onDismiss = {},
            )
        }
        onNodeWithTag(DayViewTestTags.DetourCategoryField).performTextInput("café")
        onNodeWithTag(DayViewTestTags.DetourStartAdjust).performClick()
        onNodeWithTag(DayViewTestTags.DetourStartValue).performClick()
        onNodeWithTag(DayViewTestTags.DetourStartField).performImeAction()
        onNodeWithTag(DayViewTestTags.DetourConfirm).performClick()

        // An untouched draft must not pin: the start stays "ends now".
        assertNull(start)
    }

    @Test
    fun listRowShowsDescriptionWhenPresent() = runComposeUiTest {
        val now = midWindowNow()
        val dayStart = startOfLocalDay(now)
        setContent {
            DetourListContent(
                episodes = listOf(detourEpisodeAt(now, 12 * 60, 15, "Slack", "reading threads")),
                now = now,
                windowStart = dayStart,
                windowEnd = dayStart + (23 * 60 + 59).minutes,
                onUpdate = { _, _ -> },
                onRemove = {},
                onAdd = {},
                onDismiss = {},
            )
        }
        onNodeWithTag(DayViewTestTags.DetourDescriptionText, useUnmergedTree = true).assertExists()
    }

    @Test
    fun startButtonFiresOnStartWithCategoryAndDescription() = runComposeUiTest {
        var started: Pair<String, String>? = null
        setContent {
            DetourCaptureContent(
                recentCategories = emptyList(),
                now = midWindowNow(),
                onConfirm = { _, _, _, _ -> },
                onForget = {},
                onDismiss = {},
                onStart = { category, description -> started = category to description },
            )
        }
        onNodeWithTag(DayViewTestTags.DetourCategoryField).performTextInput("Réunion")
        onNodeWithTag(DayViewTestTags.DetourStartOpen).performClick()
        assertEquals("Réunion" to "", started)
    }
}
