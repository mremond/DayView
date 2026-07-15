package fr.dayview.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
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
    fun quickActionsExposeLargeTargetsAndInvokeBothActions() = runComposeUiTest {
        var detoursAdded = 0
        var obligationsOpened = 0
        setContent {
            DayViewTheme {
                TodayQuickActions(
                    activeObligationCount = 2,
                    onAddDetour = { detoursAdded++ },
                    onOpenObligations = { obligationsOpened++ },
                )
            }
        }
        onNodeWithTag(DayViewTestTags.TodayQuickActions).assertExists()
        onNodeWithTag(DayViewTestTags.AddDetourQuickAction)
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        onNodeWithTag(DayViewTestTags.OpenObligationsQuickAction)
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, detoursAdded)
        assertEquals(1, obligationsOpened)
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
