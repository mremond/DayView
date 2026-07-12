package fr.dayview.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DisplaySettingsFontScaleTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun sliderRendersAndReportsChanges() = runComposeUiTest {
        var reported: Float? = null
        setContent {
            DayViewTheme {
                DisplaySettingsScreen(
                    state = seededController(DayPreferencesSnapshot(fontScale = 1.0f)).state,
                    platformState = SettingsPlatformUiState(
                        monochromeMenuBarIcon = null,
                        launchAtLogin = null,
                    ),
                    actions = noopSettingsActions(changeFontScale = { reported = it }),
                )
            }
        }

        onNodeWithTag(DayViewTestTags.SettingsFontScale).assertExists()
        onNodeWithTag(DayViewTestTags.SettingsFontScale)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1.5f) }

        assertNotNull(reported)
        assertTrue(reported!! > 1.0f)
    }
}
