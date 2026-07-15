package fr.dayview.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory [DayPreferences] fake shared by unit tests. */
internal class InMemoryDayPreferences(
    initial: DayPreferencesSnapshot = DayPreferencesSnapshot(),
) : DayPreferences {
    private val state = MutableStateFlow(initial)

    override val snapshots: Flow<DayPreferencesSnapshot> = state.asStateFlow()

    override suspend fun persist(snapshot: DayPreferencesSnapshot) {
        state.value = snapshot
    }

    val current: DayPreferencesSnapshot get() = state.value

    /** Simulates a write that lands from outside this process (tile/widget/alarm). */
    fun emitExternal(snapshot: DayPreferencesSnapshot) {
        state.value = snapshot
    }
}
