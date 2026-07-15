package fr.dayview.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DayViewToastTest {
    @Test
    fun rendersMessageAndActionInvokesCallback() = runComposeUiTest {
        var actioned = false
        setContent {
            DayViewTheme {
                DayViewToast(
                    visuals = ToastVisuals(
                        message = "Detour removed",
                        severity = ToastSeverity.Success,
                        actionLabelText = "UNDO",
                    ),
                    onAction = { actioned = true },
                )
            }
        }
        onNodeWithTag(DayViewTestTags.Toast).assertExists()
        onNodeWithTag(DayViewTestTags.ToastAction).assertExists()
        onNodeWithTag(DayViewTestTags.ToastAction).performClick()
        assertTrue(actioned)
    }

    @Test
    fun rendersWithoutActionWhenNoActionLabel() = runComposeUiTest {
        setContent {
            DayViewTheme {
                DayViewToast(
                    visuals = ToastVisuals(message = "Up to date", severity = ToastSeverity.Success),
                    onAction = {},
                )
            }
        }
        onNodeWithTag(DayViewTestTags.Toast).assertExists()
        onNodeWithTag(DayViewTestTags.ToastAction).assertDoesNotExist()
    }
}
