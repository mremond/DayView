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
            DayViewTheme {
                PlannedObligationsContent(
                    obligations = listOf("a", "b", "c"),
                    onAdd = {},
                    onComplete = {},
                    onRemove = {},
                    onDismiss = {},
                )
            }
        }
        onNodeWithTag(DayViewTestTags.PlannedObligationInput).assertDoesNotExist()
    }

    @Test
    fun addFieldHiddenWhenSlotsSpentByCompletions() = runComposeUiTest {
        setContent {
            DayViewTheme {
                PlannedObligationsContent(
                    obligations = listOf("a"),
                    completedObligations = listOf("b", "c"),
                    onAdd = {},
                    onComplete = {},
                    onRemove = {},
                    onDismiss = {},
                )
            }
        }
        onNodeWithTag(DayViewTestTags.PlannedObligationInput).assertDoesNotExist()
        onNodeWithTag(DayViewTestTags.PlannedObligationsCapHint).assertExists()
    }

    @Test
    fun addFieldVisibleBelowTheCap() = runComposeUiTest {
        setContent {
            DayViewTheme {
                PlannedObligationsContent(
                    obligations = listOf("a"),
                    onAdd = {},
                    onComplete = {},
                    onRemove = {},
                    onDismiss = {},
                )
            }
        }
        onNodeWithTag(DayViewTestTags.PlannedObligationInput).assertExists()
        onNodeWithTag(DayViewTestTags.PlannedObligationsCapHint).assertDoesNotExist()
    }

    @Test
    fun doneReportsTheRowMotif() = runComposeUiTest {
        var completed: String? = null
        setContent {
            DayViewTheme {
                PlannedObligationsContent(
                    obligations = listOf("Appel client"),
                    onAdd = {},
                    onComplete = { completed = it },
                    onRemove = {},
                    onDismiss = {},
                )
            }
        }
        onNodeWithTag(DayViewTestTags.PlannedObligationDone).performClick()
        assertEquals("Appel client", completed)
    }

    @Test
    fun removeReportsTheRowMotif() = runComposeUiTest {
        var removed: String? = null
        setContent {
            DayViewTheme {
                PlannedObligationsContent(
                    obligations = listOf("Appel client"),
                    onAdd = {},
                    onComplete = {},
                    onRemove = { removed = it },
                    onDismiss = {},
                )
            }
        }
        onNodeWithTag(DayViewTestTags.PlannedObligationRemove).performClick()
        assertEquals("Appel client", removed)
    }

    @Test
    fun chipShowsOpenAndDoneCountsAndOpens() = runComposeUiTest {
        var opened = false
        setContent {
            DayViewTheme {
                PlannedObligationsChip(activeCount = 2, completedCount = 1, onOpen = { opened = true })
            }
        }
        onNodeWithTag(DayViewTestTags.PlannedObligationsChip).performClick()
        assertEquals(true, opened)
    }

    @Test
    fun completedObligationsAreShownSeparately() = runComposeUiTest {
        setContent {
            DayViewTheme {
                PlannedObligationsContent(
                    obligations = listOf("Préparer la démo"),
                    completedObligations = listOf("Appel client"),
                    onAdd = {},
                    onComplete = {},
                    onRemove = {},
                    onDismiss = {},
                )
            }
        }
        onNodeWithTag(DayViewTestTags.PlannedObligationsActiveSection).assertExists()
        onNodeWithTag(DayViewTestTags.PlannedObligationsCompletedSection).assertExists()
    }
}
