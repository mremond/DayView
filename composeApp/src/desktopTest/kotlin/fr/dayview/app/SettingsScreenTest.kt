package fr.dayview.app

import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {
    private val platform = SettingsPlatformUiState(monochromeMenuBarIcon = null, launchAtLogin = null)

    @Test
    fun rendersSeededDayRangeAndShowSeconds() = runComposeUiTest {
        val snapshot = DayPreferencesSnapshot(startMinutes = 8 * 60, endMinutes = 18 * 60, showSeconds = true)
        setContent {
            val state = remember { seededController(snapshot).state }
            DayViewTheme {
                SettingsScreen(state = state, platformState = platform, actions = noopSettingsActions())
            }
        }
        onNodeWithText("08:00").assertExists()
        onNodeWithText("18:00").assertExists()
        onNodeWithTag(DayViewTestTags.SettingsShowSeconds).assertIsOn()
    }

    @Test
    fun togglingShowSecondsInvokesCallback() = runComposeUiTest {
        val snapshot = DayPreferencesSnapshot(showSeconds = true)
        var recorded: Boolean? = null
        setContent {
            val state = remember { seededController(snapshot).state }
            DayViewTheme {
                SettingsScreen(
                    state = state,
                    platformState = platform,
                    actions = noopSettingsActions(changeShowSeconds = { recorded = it }),
                )
            }
        }
        onNodeWithTag(DayViewTestTags.SettingsShowSeconds).performClick()
        assertEquals(false, recorded)
    }

    @Test
    fun backLinkInvokesCallback() = runComposeUiTest {
        var backCalled = false
        setContent {
            val state = remember { seededController(DayPreferencesSnapshot()).state }
            DayViewTheme {
                SettingsScreen(
                    state = state,
                    platformState = platform,
                    actions = noopSettingsActions(back = { backCalled = true }),
                )
            }
        }
        onNodeWithTag(DayViewTestTags.SettingsBack).performClick()
        assertTrue(backCalled)
    }

    @Test
    fun rendersSoundPanel() = runComposeUiTest {
        setContent {
            val state = remember { seededController(DayPreferencesSnapshot()).state }
            DayViewTheme {
                SettingsScreen(state = state, platformState = platform, actions = noopSettingsActions())
            }
        }
        assertTextEventuallyExists("SONS")
    }
}
