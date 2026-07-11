package fr.dayview.app

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ComposeUiTestSmokeTest {
    @Test
    fun harnessRendersAndFindsText() = runComposeUiTest {
        setContent { Text("dayview-smoke") }
        onNodeWithText("dayview-smoke").assertExists()
    }
}
