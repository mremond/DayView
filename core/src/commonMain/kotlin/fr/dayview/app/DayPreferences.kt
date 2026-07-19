package fr.dayview.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Instant

data class DayPreferencesSnapshot(
    val startMinutes: Int = 8 * 60,
    val endMinutes: Int = 18 * 60,
    val showSeconds: Boolean = true,
    val soundSettings: SoundSettings = SoundSettings(),
    val goalTitle: String = "",
    val goalDeadline: Instant? = null,
    val goalStart: Instant? = null,
    val pomodoroMinutes: Int = 25,
    val pomodoroEnd: Instant? = null,
    val pomodoroSessionMinutes: Int? = null,
    val breakStart: Instant? = null,
    val focusIntention: String = "",
    val openDetourStart: Instant? = null,
    val openDetourCategory: String = "",
    val openDetourDescription: String = "",
    val netTimeSettings: NetTimeSettings = NetTimeSettings(),
    val onGoalApps: Set<AppRef> = emptySet(),
    val busyDayKey: Long = -1L,
    val busyIntervals: List<BusyInterval> = emptyList(),
    val availableCalendars: List<CalendarInfo> = emptyList(),
    val detoursDayKey: Long = -1L,
    val detours: List<DetourEpisode> = emptyList(),
    val focusSessionDayKey: Long = -1L,
    val focusSessionIntervals: List<FocusPresenceInterval> = emptyList(),
    val focusSessionRecordsDayKey: Long = -1L,
    val focusSessionRecords: List<FocusSessionRecord> = emptyList(),
    val recentDetourCategories: List<String> = emptyList(),
    val plannedObligationsDayKey: Long = -1L,
    val plannedObligations: List<String> = emptyList(),
    val plannedObligationsCompleted: List<String> = emptyList(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val cleanSessions: CleanSessionLedger = CleanSessionLedger(),
    val fontScale: Float = 1.0f,
)

/**
 * The snapshot-side twin of [DayViewUiState.focusIsActive], for the loops that read
 * persisted preferences rather than controller state (the desktop tick loop).
 */
val DayPreferencesSnapshot.focusIsActive: Boolean
    get() = focusSessionIsOpen(pomodoroEnd)

/**
 * The snapshot-side twin of [DayViewUiState.sessionMinutesEffective]: the running session's
 * own snapshotted duration, falling back to the preferred one between sessions. Changing the
 * preference mid-session must never reach the window that is running.
 */
val DayPreferencesSnapshot.sessionMinutesEffective: Int
    get() = pomodoroSessionMinutes ?: pomodoroMinutes

interface DayPreferences {
    val snapshots: Flow<DayPreferencesSnapshot>
    suspend fun persist(snapshot: DayPreferencesSnapshot)
}

object DefaultDayPreferences : DayPreferences {
    private val state = MutableStateFlow(DayPreferencesSnapshot())
    override val snapshots: Flow<DayPreferencesSnapshot> = state.asStateFlow()
    override suspend fun persist(snapshot: DayPreferencesSnapshot) {
        state.value = snapshot
    }
}
