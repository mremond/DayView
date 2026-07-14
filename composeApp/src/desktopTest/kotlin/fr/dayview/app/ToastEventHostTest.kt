package fr.dayview.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ToastEventHostTest {
    @Test
    fun detourRemovedToastShowsAndActionInvokesUndo() = runComposeUiTest {
        val bus = AppEventBus()
        var undone = false
        setContent {
            DayViewTheme {
                Box(Modifier.fillMaxSize()) {
                    ToastEventHost(
                        events = bus.events,
                        hostState = remember { SnackbarHostState() },
                        onUndoDetour = { undone = true },
                        onUndoObligation = {},
                    )
                }
            }
        }
        waitForIdle()
        bus.post(AppEvent.Toast(ToastKind.DetourRemoved, "Social"))
        waitUntil { onAllNodesWithTag(DayViewTestTags.Toast).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(DayViewTestTags.ToastAction).performClick()
        waitForIdle()
        assertTrue(undone)
    }

    @Test
    fun obligationRemovedToastShowsAndActionInvokesUndo() = runComposeUiTest {
        val bus = AppEventBus()
        var undone = false
        setContent {
            DayViewTheme {
                Box(Modifier.fillMaxSize()) {
                    ToastEventHost(
                        events = bus.events,
                        hostState = remember { SnackbarHostState() },
                        onUndoDetour = {},
                        onUndoObligation = { undone = true },
                    )
                }
            }
        }
        waitForIdle()
        bus.post(AppEvent.Toast(ToastKind.ObligationRemoved, "Alpha"))
        waitUntil { onAllNodesWithTag(DayViewTestTags.Toast).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(DayViewTestTags.ToastAction).performClick()
        waitForIdle()
        assertTrue(undone)
    }

    @Test
    fun toastWithoutActionShowsNoActionNode() = runComposeUiTest {
        val bus = AppEventBus()
        setContent {
            DayViewTheme {
                Box(Modifier.fillMaxSize()) {
                    ToastEventHost(
                        events = bus.events,
                        hostState = remember { SnackbarHostState() },
                        onUndoDetour = {},
                        onUndoObligation = {},
                    )
                }
            }
        }
        waitForIdle()
        bus.post(AppEvent.Toast(ToastKind.SyncSucceeded))
        waitUntil { onAllNodesWithTag(DayViewTestTags.Toast).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(DayViewTestTags.ToastAction).assertDoesNotExist()
    }
}
