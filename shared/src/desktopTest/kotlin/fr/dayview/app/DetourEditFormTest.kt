package fr.dayview.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DetourEditFormTest {
    private fun localMinutes(episode: DetourEpisode): Int {
        val local = episode.start.toLocalDateTime(TimeZone.currentSystemDefault())
        return local.hour * 60 + local.minute
    }

    @Test
    fun nudgesSnapMisalignedStartAndDurationToMultiplesOfFive() = runComposeUiTest {
        var saved: DetourEpisode? = null
        setContent {
            DetourEditForm(
                initial = detourEpisodeAt(midWindowNow(), 14 * 60 + 32, 17, "vélo"),
                now = midWindowNow(),
                onDelete = null,
                onCancel = {},
                onSave = { saved = it },
            )
        }
        onNodeWithTag(DayViewTestTags.DetourEditStartIncrease).performClick()
        onNodeWithTag(DayViewTestTags.DetourEditDurationIncrease).performClick()
        onNodeWithTag(DayViewTestTags.DetourEditSave).performClick()

        // 14:32 + snaps to 14:35; 17 min + snaps to 20 min.
        assertEquals(14 * 60 + 35, localMinutes(saved!!))
        assertEquals(20, saved!!.duration.inWholeMinutes.toInt())
    }

    @Test
    fun durationChangeKeepsRetroactiveAddEndingNow() = runComposeUiTest {
        var saved: DetourEpisode? = null
        setContent {
            DetourEditForm(
                initial = null,
                now = midWindowNow(),
                onDelete = null,
                onCancel = {},
                onSave = { saved = it },
            )
        }
        onNodeWithTag(DayViewTestTags.DetourCategoryField).performTextInput("vélo")
        onNodeWithTag(DayViewTestTags.DetourEditDurationIncrease).performClick()
        onNodeWithTag(DayViewTestTags.DetourEditSave).performClick()

        // now is 13:00; 15 min + snaps to 20 min and the start follows so the end stays at now.
        assertEquals(12 * 60 + 40, localMinutes(saved!!))
        assertEquals(20, saved!!.duration.inWholeMinutes.toInt())
    }

    @Test
    fun durationChangeKeepsExistingEpisodeEndAnchored() = runComposeUiTest {
        var saved: DetourEpisode? = null
        setContent {
            DetourEditForm(
                initial = detourEpisodeAt(midWindowNow(), 14 * 60 + 30, 15, "vélo"),
                now = midWindowNow(),
                onDelete = null,
                onCancel = {},
                onSave = { saved = it },
            )
        }
        onNodeWithTag(DayViewTestTags.DetourEditDurationIncrease).performClick()
        onNodeWithTag(DayViewTestTags.DetourEditSave).performClick()

        // 14:30–14:45 grows backwards: 20 min starting 14:25, end unchanged.
        assertEquals(14 * 60 + 25, localMinutes(saved!!))
        assertEquals(20, saved!!.duration.inWholeMinutes.toInt())
    }

    @Test
    fun typedStartAndDurationAreSaved() = runComposeUiTest {
        var saved: DetourEpisode? = null
        setContent {
            DetourEditForm(
                initial = detourEpisodeAt(midWindowNow(), 14 * 60 + 32, 17, "vélo"),
                now = midWindowNow(),
                onDelete = null,
                onCancel = {},
                onSave = { saved = it },
            )
        }
        onNodeWithTag(DayViewTestTags.DetourEditStartValue).performClick()
        onNodeWithTag(DayViewTestTags.DetourEditStartField).performTextClearance()
        onNodeWithTag(DayViewTestTags.DetourEditStartField).performTextInput("9h05")
        onNodeWithTag(DayViewTestTags.DetourEditStartField).performImeAction()
        onNodeWithTag(DayViewTestTags.DetourEditDurationValue).performClick()
        onNodeWithTag(DayViewTestTags.DetourEditDurationField).performTextClearance()
        onNodeWithTag(DayViewTestTags.DetourEditDurationField).performTextInput("45")
        onNodeWithTag(DayViewTestTags.DetourEditDurationField).performImeAction()
        onNodeWithTag(DayViewTestTags.DetourEditSave).performClick()

        assertEquals(9 * 60 + 5, localMinutes(saved!!))
        assertEquals(45, saved!!.duration.inWholeMinutes.toInt())
    }
}
