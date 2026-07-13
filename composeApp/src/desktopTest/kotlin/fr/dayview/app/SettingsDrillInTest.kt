package fr.dayview.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SettingsDrillInTest {
    private val platform = SettingsPlatformUiState(monochromeMenuBarIcon = null, launchAtLogin = null)

    @Test
    fun drillingIntoSoundsShowsTheSubScreenThenBackReturnsToList() = runComposeUiTest {
        setContent {
            val controller = remember { seededController(DayPreferencesSnapshot()) }
            val state by controller.stateFlow.collectAsState()
            DayViewTheme {
                SettingsScreen(
                    state = state,
                    platformState = platform,
                    actions = noopSettingsActions(
                        openCategory = { controller.openSettingsCategory(it) },
                        closeCategory = { controller.closeSettingsCategory() },
                    ),
                )
            }
        }
        // Landing → Sounds sub-screen.
        onNodeWithTag(DayViewTestTags.settingsCategoryRow(SettingsCategory.SOUNDS)).performClick()
        onNodeWithTag(DayViewTestTags.SettingsSoundsScreen).assertExists()
        onNodeWithTag(DayViewTestTags.settingsCategoryRow(SettingsCategory.SOUNDS)).assertDoesNotExist()
        // Back → landing list.
        onNodeWithTag(DayViewTestTags.SettingsBack).performClick()
        onNodeWithTag(DayViewTestTags.settingsCategoryRow(SettingsCategory.SOUNDS)).assertExists()
    }
}
