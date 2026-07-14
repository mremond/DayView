package fr.dayview.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SyncConfirmDialogContentTest {
    @Test
    fun confirmButtonInvokesOnConfirm() = runComposeUiTest {
        var confirmed = false
        setContent {
            DayViewTheme {
                SyncConfirmDialogContent(
                    title = "Regenerate key?",
                    message = "The old key will be lost.",
                    confirmLabel = "REGENERATE",
                    onConfirm = { confirmed = true },
                    onDismiss = {},
                )
            }
        }
        onNodeWithTag(DayViewTestTags.SyncConfirmDialogConfirm).performClick()
        assertTrue(confirmed)
    }

    @Test
    fun cancelButtonInvokesOnDismiss() = runComposeUiTest {
        var dismissed = false
        setContent {
            DayViewTheme {
                SyncConfirmDialogContent(
                    title = "Regenerate key?",
                    message = "The old key will be lost.",
                    confirmLabel = "REGENERATE",
                    onConfirm = {},
                    onDismiss = { dismissed = true },
                )
            }
        }
        onNodeWithTag(DayViewTestTags.SyncConfirmDialogCancel).performClick()
        assertTrue(dismissed)
    }
}
