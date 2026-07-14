package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
                SettingsCategory.SYNC,
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
                SettingsCategory.SYNC,
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
            listOf(SettingsCategory.DAY, SettingsCategory.DISPLAY, SettingsCategory.SOUNDS, SettingsCategory.SYNC),
            settingsCategoriesFor(platform),
        )
    }

    private fun platformState(powerManagementSupported: Boolean) = SettingsPlatformUiState(
        monochromeMenuBarIcon = null,
        launchAtLogin = null,
        powerManagementSupported = powerManagementSupported,
    )

    @Test
    fun systemCategoryIsListedWhenPowerManagementSupported() {
        assertTrue(SettingsCategory.SYSTEM in settingsCategoriesFor(platformState(true)))
    }

    @Test
    fun systemCategoryIsHiddenWhenUnsupported() {
        assertFalse(SettingsCategory.SYSTEM in settingsCategoriesFor(platformState(false)))
    }
}
