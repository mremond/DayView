package fr.dayview.app

import android.content.Context
import androidx.annotation.VisibleForTesting

private class WidgetRefreshingPreferences(
    private val delegate: DayPreferencesStore,
    private val appContext: Context,
) : DayPreferences {
    override val snapshots = delegate.snapshots
    override suspend fun persist(snapshot: DayPreferencesSnapshot) {
        delegate.persist(snapshot)
        DayViewWidget.render(appContext, snapshot)
    }
}

object DayViewPreferences {
    @Volatile
    private var instance: DayPreferences? = null

    fun get(context: Context): DayPreferences {
        val app = context.applicationContext
        return instance ?: synchronized(this) {
            instance ?: WidgetRefreshingPreferences(androidDayPreferences(app), app).also { instance = it }
        }
    }

    /**
     * Overrides the process-wide instance with a test double. Robolectric shares a single JVM
     * across test classes, so tests inject an in-memory [DayPreferences] here to stay isolated
     * from the real DataStore and from each other.
     */
    @VisibleForTesting
    internal fun setForTest(preferences: DayPreferences) {
        synchronized(this) { instance = preferences }
    }

    /**
     * Clears the cached instance so it does not leak into the next test. Pair with an
     * `@After` hook; the following [get] rebuilds (or a fresh [setForTest] replaces) it.
     */
    @VisibleForTesting
    internal fun resetForTest() {
        synchronized(this) { instance = null }
    }
}
