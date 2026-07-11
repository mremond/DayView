package fr.dayview.app

/**
 * Stable identifiers for Compose UI tests (see composeApp/src/desktopTest).
 * Kept in commonMain so production composables and desktop tests share one
 * source of truth and cannot drift.
 */
@Suppress("ktlint:standard:property-naming")
internal object DayViewTestTags {
    const val Countdown = "dayViewCountdown"
    const val FocusStart = "focusStart"
    const val FocusStop = "focusStop"
    const val SettingsShowSeconds = "settingsShowSeconds"
    const val SettingsBack = "settingsBack"
}
