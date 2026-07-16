package fr.dayview.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

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
        val session = DayViewSession(controller, scope, source)
        // After the user answers the access prompt, re-read immediately instead of
        // waiting for the next minute tick.
        source.onPermissionChange = { session.refreshCalendar() }
        return session
    }
}
