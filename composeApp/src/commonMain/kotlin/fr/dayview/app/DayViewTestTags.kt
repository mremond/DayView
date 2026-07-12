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
    const val FocusStart = "focusStart"
    const val FocusStop = "focusStop"
    const val SettingsShowSeconds = "settingsShowSeconds"
    const val SettingsBack = "settingsBack"
    const val SettingsSounds = "settingsSounds"
}
