package fr.dayview.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSBundle
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
        val session = DayViewSession(
            controller,
            scope,
            source,
            use24Hour = systemUses24HourClock(),
            frontmostAppProvider = NSWorkspaceFrontmostProvider(),
            // The Debug build ships under a distinct bundle id (fr.dayview.app.debug) so it can
            // coexist with the shipping Compose app; deriving from the running bundle (rather
            // than the DAYVIEW_BUNDLE_ID default) keeps DayView itself classified NEUTRAL in
            // every config instead of only in Release.
            dayViewBundleId = NSBundle.mainBundle.bundleIdentifier ?: DAYVIEW_BUNDLE_ID,
        )
        // After the user answers the access prompt, re-read immediately instead of
        // waiting for the next minute tick.
        source.onPermissionChange = { session.refreshCalendar() }
        return session
    }
}
