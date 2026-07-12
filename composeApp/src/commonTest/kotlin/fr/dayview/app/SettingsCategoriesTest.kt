package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsCategoriesTest {
    @Test
    fun androidLikePlatformOmitsDesktopOnlyCategories() {
        val platform = SettingsPlatformUiState(
            monochromeMenuBarIcon = null,
            launchAtLogin = null,
            netTimeSupported = true,
            onGoalSupported = false,
        )

        assertEquals(
            listOf(
                SettingsCategory.DAY,
                SettingsCategory.DISPLAY,
                SettingsCategory.SOUNDS,
                SettingsCategory.NET_TIME,
            ),
            settingsCategoriesFor(platform),
        )
    }

    @Test
    fun desktopPlatformIncludesOnGoalWhenSupported() {
        val platform = SettingsPlatformUiState(
            monochromeMenuBarIcon = true,
            launchAtLogin = true,
            netTimeSupported = true,
            onGoalSupported = true,
        )

        assertEquals(
            listOf(
                SettingsCategory.DAY,
                SettingsCategory.DISPLAY,
                SettingsCategory.SOUNDS,
                SettingsCategory.NET_TIME,
                SettingsCategory.ON_GOAL,
            ),
            settingsCategoriesFor(platform),
        )
    }

    @Test
    fun dayDisplaySoundsAreAlwaysPresent() {
        val platform = SettingsPlatformUiState(
            monochromeMenuBarIcon = null,
            launchAtLogin = null,
            netTimeSupported = false,
            onGoalSupported = false,
        )

        assertEquals(
            listOf(SettingsCategory.DAY, SettingsCategory.DISPLAY, SettingsCategory.SOUNDS),
            settingsCategoriesFor(platform),
        )
    }
}
