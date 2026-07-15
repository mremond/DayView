@file:OptIn(ExperimentalTestApi::class)

package fr.dayview.app

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.time.Instant

class DetourReadoutDetailsTest {
    private fun ms(ms: Long): Instant = Instant.fromEpochMilliseconds(ms)

    private fun body(description: String) = DetourBody(
        startAngleDegrees = 0f,
        sweepDegrees = 10f,
        colorIndex = 0,
        category = "Slack",
        description = description,
        start = ms(0L),
        end = ms(45L * 60 * 1000), // 45 min -> formatDurationHm == "45 min"
    )

    @Test
    fun showsCategoryDescriptionAndDuration() = runComposeUiTest {
        setContent {
            Column { DetourReadoutDetails(body("urgent thread"), categoryColor = Color.White) }
        }
        onNodeWithText("Slack").assertExists()
        onNodeWithText("urgent thread").assertExists()
        onNodeWithText("45 min").assertExists()
    }

    @Test
    fun omitsDescriptionWhenBlank() = runComposeUiTest {
        setContent {
            Column { DetourReadoutDetails(body(""), categoryColor = Color.White) }
        }
        onNodeWithText("Slack").assertExists()
        onNodeWithText("45 min").assertExists()
        onNodeWithText("urgent thread").assertDoesNotExist()
    }
}
