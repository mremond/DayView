package fr.dayview.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory [DayPreferences] fake for :androidApp component tests. Mirrors :shared's own test
 * fake, kept local because test source sets are not shared across module boundaries.
 */
internal class FakeDayPreferences(
    initial: DayPreferencesSnapshot = DayPreferencesSnapshot(),
) : DayPreferences {
    private val state = MutableStateFlow(initial)

    override val snapshots: Flow<DayPreferencesSnapshot> = state.asStateFlow()

    override suspend fun persist(snapshot: DayPreferencesSnapshot) {
        state.value = snapshot
    }
}
