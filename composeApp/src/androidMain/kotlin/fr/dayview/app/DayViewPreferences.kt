package fr.dayview.app

import android.content.Context

private class WidgetRefreshingPreferences(
    private val delegate: DayPreferencesStore,
    private val appContext: Context,
) : DayPreferences {
    override val snapshots = delegate.snapshots
    override suspend fun persist(snapshot: DayPreferencesSnapshot) {
        delegate.persist(snapshot)
        DayViewWidget.updateAll(appContext)
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
}
