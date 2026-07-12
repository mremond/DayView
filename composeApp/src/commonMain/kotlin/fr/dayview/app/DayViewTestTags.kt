package fr.dayview.app

/**
 * Stable identifiers for Compose UI tests (see composeApp/src/desktopTest).
 * Kept in commonMain so production composables and desktop tests share one
 * source of truth and cannot drift.
 */
// PascalCase constant names read more naturally at the testTag/onNodeWithTag
// call sites than the SCREAMING_SNAKE_CASE ktlint's property-naming rule wants.
@Suppress("ktlint:standard:property-naming")
internal object DayViewTestTags {
    const val Countdown = "dayViewCountdown"
    const val CleanSessions = "cleanSessions"
    const val MiniWindow = "miniWindowButton"
    const val OpenMainWindow = "openMainWindowButton"
    const val FocusStart = "focusStart"
    const val FocusStop = "focusStop"
    const val DetourMotifField = "detourMotifField"
    const val DetourStartAdjust = "detourStartAdjust"
    const val DetourStartIncrease = "detourStartIncrease"
    const val DetourConfirm = "detourConfirm"
    const val SettingsShowSeconds = "settingsShowSeconds"
    const val SettingsThemeMode = "settingsThemeMode"
    const val SettingsThemeSystem = "settingsThemeSystem"
    const val SettingsThemeLight = "settingsThemeLight"
    const val SettingsThemeDark = "settingsThemeDark"
    const val SettingsFontScale = "settingsFontScale"
    const val SettingsBack = "settingsBack"
    const val SettingsDayScreen = "settingsDayScreen"
    const val SettingsDisplayScreen = "settingsDisplayScreen"
    const val SettingsSoundsScreen = "settingsSoundsScreen"
    const val SettingsNetTimeScreen = "settingsNetTimeScreen"
    const val SettingsOnGoalScreen = "settingsOnGoalScreen"
    const val MiniRing = "historyMiniRing"
    const val HistoryIcon = "historyIcon"
    const val HistoryBack = "historyBack"

    fun settingsCategoryRow(category: SettingsCategory): String = "settingsCategoryRow_${category.name}"

    fun historyDayCell(dayKey: Long): String = "historyDayCell_$dayKey"
}
