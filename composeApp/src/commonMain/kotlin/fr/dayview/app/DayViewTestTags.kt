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
    const val FocusRecap = "focusRecap"
    const val MiniWindow = "miniWindowButton"
    const val MiniFocusRelaunch = "miniFocusRelaunchButton"
    const val OpenMainWindow = "openMainWindowButton"
    const val FocusStart = "focusStart"
    const val FocusStop = "focusStop"
    const val FocusRelaunch = "focusRelaunch"
    const val DetourMotifField = "detourMotifField"
    const val DetourStartAdjust = "detourStartAdjust"
    const val DetourStartIncrease = "detourStartIncrease"
    const val DetourStartDecrease = "detourStartDecrease"
    const val DetourStartValue = "detourStartValue"
    const val DetourStartField = "detourStartField"
    const val DetourEditStartValue = "detourEditStartValue"
    const val DetourEditStartField = "detourEditStartField"
    const val DetourEditStartIncrease = "detourEditStartIncrease"
    const val DetourEditDurationValue = "detourEditDurationValue"
    const val DetourEditDurationField = "detourEditDurationField"
    const val DetourEditDurationIncrease = "detourEditDurationIncrease"
    const val DetourEditSave = "detourEditSave"
    const val DetourConfirm = "detourConfirm"
    const val DetourLongToggle = "detourLongToggle"
    const val PlannedObligationInput = "plannedObligationInput"
    const val PlannedObligationAdd = "plannedObligationAdd"
    const val PlannedObligationDone = "plannedObligationDone"
    const val PlannedObligationsChip = "plannedObligationsChip"
    const val PlannedObligationRemove = "plannedObligationRemove"
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
    const val SyncSettingsScreen = "syncSettingsScreen"
    const val SyncSettingsUrl = "syncSettingsUrl"
    const val SyncSettingsUser = "syncSettingsUser"
    const val SyncSettingsToken = "syncSettingsToken"
    const val SyncSettingsGenerateKey = "syncSettingsGenerateKey"
    const val SyncSettingsGeneratedKey = "syncSettingsGeneratedKey"
    const val SyncSettingsPasteKey = "syncSettingsPasteKey"
    const val SyncSettingsSyncNow = "syncSettingsSyncNow"
    const val SyncSettingsStatus = "syncSettingsStatus"
    const val SyncSettingsClear = "syncSettingsClear"
    const val MiniRing = "historyMiniRing"
    const val HistoryIcon = "historyIcon"
    const val HistoryBack = "historyBack"

    fun focusOutcome(outcome: FocusClosureOutcome): String = "focusOutcome_${outcome.name}"

    fun settingsCategoryRow(category: SettingsCategory): String = "settingsCategoryRow_${category.name}"

    fun historyDayCell(dayKey: Long): String = "historyDayCell_$dayKey"

    fun detourDurationChip(minutes: Int): String = "detourDuration$minutes"
}
