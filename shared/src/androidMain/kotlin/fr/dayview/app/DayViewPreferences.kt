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
        DayViewPreferences.snapshotListener?.invoke(appContext, snapshot)
    }
}

object DayViewPreferences {
    /**
     * Side effect run after each snapshot persists. `:androidApp` registers the home-screen
     * widget refresh here (`DayViewWidget` is a manifest component living in `:androidApp`, so
     * `:shared` cannot reference it directly). Left null on desktop and in tests, where there is
     * no widget to redraw.
     */
    @Volatile
    var snapshotListener: ((Context, DayPreferencesSnapshot) -> Unit)? = null

    @Volatile
    private var instance: DayPreferences? = null

    @Volatile
    private var historyStoreInstance: DayHistoryStore? = null

    @Volatile
    private var focusStoreInstance: FocusContributionStore? = null

    fun get(context: Context): DayPreferences {
        val app = context.applicationContext
        return instance ?: synchronized(this) {
            instance ?: WidgetRefreshingPreferences(androidDayPreferences(app), app).also { instance = it }
        }
    }

    /**
     * Process-wide history store, backed by the app's files directory. `initCalendarSource`
     * must have run first (it sets the app context [createHistoryFileSystem] reads from);
     * `MainActivity.onCreate` guarantees this ordering.
     */
    fun history(): DayHistoryStore = historyStoreInstance ?: synchronized(this) {
        historyStoreInstance ?: createDayHistoryStore().also { historyStoreInstance = it }
    }

    /**
     * Process-wide focus contribution store, mirroring [history]'s lazy singleton and the same
     * app-context ordering requirement.
     */
    fun focusContributions(): FocusContributionStore = focusStoreInstance ?: synchronized(this) {
        focusStoreInstance ?: createFocusContributionStore().also { focusStoreInstance = it }
    }

    /**
     * Overrides the process-wide instance with a test double. Robolectric shares a single JVM
     * across test classes, so tests inject an in-memory [DayPreferences] here to stay isolated
     * from the real DataStore and from each other.
     */
    @VisibleForTesting
    fun setForTest(preferences: DayPreferences) {
        synchronized(this) { instance = preferences }
    }

    /**
     * Clears the cached instance so it does not leak into the next test. Pair with an
     * `@After` hook; the following [get] rebuilds (or a fresh [setForTest] replaces) it.
     */
    @VisibleForTesting
    fun resetForTest() {
        synchronized(this) {
            instance = null
            historyStoreInstance = null
            focusStoreInstance = null
        }
    }
}
