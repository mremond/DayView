package fr.dayview.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PlannedObligationsUiTest {
    @Test
    fun addFieldHiddenAtTheCap() = runComposeUiTest {
        setContent {
            PlannedObligationsSection(
                obligations = listOf("a", "b", "c"),
                onAdd = {},
                onComplete = {},
            )
        }
        onNodeWithTag(DayViewTestTags.PlannedObligationInput).assertDoesNotExist()
    }

    @Test
    fun doneReportsTheRowMotif() = runComposeUiTest {
        var completed: String? = null
        setContent {
            PlannedObligationsSection(
                obligations = listOf("Appel client"),
                onAdd = {},
                onComplete = { completed = it },
            )
        }
        onNodeWithTag(DayViewTestTags.PlannedObligationDone).performClick()
        assertEquals("Appel client", completed)
    }
}
