package fr.dayview.app

import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
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
    fun landingListShowsSupportedCategories() = runComposeUiTest {
        setContent {
            val controller = remember { seededController(DayPreferencesSnapshot()) }
            DayViewTheme {
                SettingsScreen(
                    state = controller.state,
                    platformState = platform,
                    actions = noopSettingsActions(),
                )
            }
        }
        onNodeWithTag(DayViewTestTags.settingsCategoryRow(SettingsCategory.DAY)).assertExists()
        onNodeWithTag(DayViewTestTags.settingsCategoryRow(SettingsCategory.DISPLAY)).assertExists()
        onNodeWithTag(DayViewTestTags.settingsCategoryRow(SettingsCategory.SOUNDS)).assertExists()
    }

    @Test
    fun drillingIntoDayShowsSeededRange() = runComposeUiTest {
        val snapshot = DayPreferencesSnapshot(startMinutes = 8 * 60, endMinutes = 18 * 60, showSeconds = true)
        setContent {
            val controller = remember { seededController(snapshot) }
            DayViewTheme {
                SettingsScreen(
                    state = controller.state,
                    platformState = platform,
                    actions = noopSettingsActions(openCategory = { controller.openSettingsCategory(it) }),
                )
            }
        }
        onNodeWithTag(DayViewTestTags.settingsCategoryRow(SettingsCategory.DAY)).performClick()
        onNodeWithText("08:00").assertExists()
        onNodeWithText("18:00").assertExists()
    }

    @Test
    fun togglingShowSecondsInDisplayInvokesCallback() = runComposeUiTest {
        var recorded: Boolean? = null
        setContent {
            val controller = remember { seededController(DayPreferencesSnapshot(showSeconds = true)) }
            DayViewTheme {
                SettingsScreen(
                    state = controller.state,
                    platformState = platform,
                    actions = noopSettingsActions(
                        changeShowSeconds = { recorded = it },
                        openCategory = { controller.openSettingsCategory(it) },
                    ),
                )
            }
        }
        onNodeWithTag(DayViewTestTags.settingsCategoryRow(SettingsCategory.DISPLAY)).performClick()
        onNodeWithTag(DayViewTestTags.SettingsShowSeconds).performClick()
        assertEquals(false, recorded)
    }

    @Test
    fun displayShowsAppearanceSelector() = runComposeUiTest {
        setContent {
            val controller = remember { seededController(DayPreferencesSnapshot(themeMode = ThemeMode.DARK)) }
            DayViewTheme {
                SettingsScreen(
                    state = controller.state,
                    platformState = platform,
                    actions = noopSettingsActions(openCategory = { controller.openSettingsCategory(it) }),
                )
            }
        }
        onNodeWithTag(DayViewTestTags.settingsCategoryRow(SettingsCategory.DISPLAY)).performClick()
        onNodeWithTag(DayViewTestTags.SettingsThemeMode).assertExists()
        onNodeWithTag(DayViewTestTags.SettingsThemeDark).assertExists()
    }

    @Test
    fun tappingLightSegmentInvokesCallback() = runComposeUiTest {
        var recorded: ThemeMode? = null
        setContent {
            val controller = remember { seededController(DayPreferencesSnapshot(themeMode = ThemeMode.SYSTEM)) }
            DayViewTheme {
                SettingsScreen(
                    state = controller.state,
                    platformState = platform,
                    actions = noopSettingsActions(
                        changeThemeMode = { recorded = it },
                        openCategory = { controller.openSettingsCategory(it) },
                    ),
                )
            }
        }
        onNodeWithTag(DayViewTestTags.settingsCategoryRow(SettingsCategory.DISPLAY)).performClick()
        onNodeWithTag(DayViewTestTags.SettingsThemeLight).performClick()
        assertEquals(ThemeMode.LIGHT, recorded)
    }

    @Test
    fun backLinkFromListInvokesCallback() = runComposeUiTest {
        var backCalled = false
        setContent {
            val controller = remember { seededController(DayPreferencesSnapshot()) }
            DayViewTheme {
                SettingsScreen(
                    state = controller.state,
                    platformState = platform,
                    actions = noopSettingsActions(back = { backCalled = true }),
                )
            }
        }
        onNodeWithTag(DayViewTestTags.SettingsBack).performClick()
        assertTrue(backCalled)
    }
}
