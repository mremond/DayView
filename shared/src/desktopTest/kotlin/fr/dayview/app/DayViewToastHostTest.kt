package fr.dayview.app

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class DayViewToastHostTest {
    @Test
    fun hostShowsToastPushedThroughState() = runComposeUiTest {
        setContent {
            DayViewTheme {
                val host = remember { SnackbarHostState() }
                DayViewToastHost(host)
                LaunchedEffect(Unit) {
                    host.showSnackbar(
                        ToastVisuals(message = "Up to date", severity = ToastSeverity.Success),
                    )
                }
            }
        }
        onNodeWithTag(DayViewTestTags.Toast).assertExists()
    }
}
