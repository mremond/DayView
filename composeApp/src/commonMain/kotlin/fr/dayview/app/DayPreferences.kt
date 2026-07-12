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
    val focusIntention: String = "",
    val netTimeSettings: NetTimeSettings = NetTimeSettings(),
    val onGoalApps: Set<AppRef> = emptySet(),
    val detoursDayKey: Long = -1L,
    val detours: List<DetourEpisode> = emptyList(),
    val recentDetourMotifs: List<String> = emptyList(),
    val plannedObligationsDayKey: Long = -1L,
    val plannedObligations: List<String> = emptyList(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val cleanSessions: CleanSessionLedger = CleanSessionLedger(),
    val fontScale: Float = 1.0f,
)

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
