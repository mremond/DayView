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
        return DayViewSession(controller, scope)
    }
}
