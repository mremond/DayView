package fr.dayview.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale

// The canonical macOS probe: format a "j" skeleton for the current locale/settings —
// an "a" (AM/PM) in the result means the system uses the 12-hour clock.
private fun systemUses24HourClock(): Boolean {
    val format = NSDateFormatter.dateFormatFromTemplate("j", options = 0u, locale = NSLocale.currentLocale)
    return format?.contains("a") != true
}

/** Single entry point Swift calls to build the whole graph with file-backed preferences. */
object DayViewNative {
    fun create(): DayViewSession {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val preferences = macosDayPreferences()
        val controller = DayViewController(
            preferences,
            scope,
            initialSnapshot = runBlocking { preferences.snapshots.first() },
        )
        val source = EventKitCalendarSource()
        val session = DayViewSession(controller, scope, source, use24Hour = systemUses24HourClock())
        // After the user answers the access prompt, re-read immediately instead of
        // waiting for the next minute tick.
        source.onPermissionChange = { session.refreshCalendar() }
        return session
    }
}
