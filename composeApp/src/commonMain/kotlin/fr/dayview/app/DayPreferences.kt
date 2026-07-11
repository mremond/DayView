package fr.dayview.app

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class DayPreferencesSnapshot(
    val startMinutes: Int = 8 * 60,
    val endMinutes: Int = 18 * 60,
    val showSeconds: Boolean = true,
    val soundSettings: SoundSettings = SoundSettings(),
    val goalTitle: String = "",
    val goalDeadlineMillis: Long? = null,
    val goalStartMillis: Long? = null,
    val pomodoroMinutes: Int = 25,
    val pomodoroEndMillis: Long? = null,
    val focusIntention: String = "",
    val netTimeSettings: NetTimeSettings = NetTimeSettings(),
    val onGoalApps: Set<AppRef> = emptySet(),
)

interface DayPreferences {
    fun loadStartMinutes(): Int
    fun loadEndMinutes(): Int
    fun saveDayRange(startMinutes: Int, endMinutes: Int)
    fun loadShowSeconds(): Boolean
    fun saveShowSeconds(showSeconds: Boolean)
    fun loadSoundSettings(): SoundSettings
    fun saveSoundSettings(settings: SoundSettings)
    fun loadGoalTitle(): String
    fun loadGoalDeadlineMillis(): Long?
    fun loadGoalStartMillis(): Long?
    fun saveGlobalGoal(title: String, deadlineMillis: Long?, startMillis: Long?)
    fun loadPomodoroMinutes(): Int
    fun loadPomodoroEndMillis(): Long?
    fun savePomodoro(durationMinutes: Int, endMillis: Long?)
    fun loadFocusIntention(): String
    fun saveFocusIntention(intention: String)
    fun loadNetTimeSettings(): NetTimeSettings
    fun saveNetTimeSettings(settings: NetTimeSettings)
    fun loadOnGoalApps(goalId: String): Set<AppRef> = emptySet()
    fun saveOnGoalApps(goalId: String, apps: Set<AppRef>) = Unit

    fun snapshot(): DayPreferencesSnapshot = DayPreferencesSnapshot(
        startMinutes = loadStartMinutes(),
        endMinutes = loadEndMinutes(),
        showSeconds = loadShowSeconds(),
        soundSettings = loadSoundSettings(),
        goalTitle = loadGoalTitle(),
        goalDeadlineMillis = loadGoalDeadlineMillis(),
        goalStartMillis = loadGoalStartMillis(),
        pomodoroMinutes = loadPomodoroMinutes(),
        pomodoroEndMillis = loadPomodoroEndMillis(),
        focusIntention = loadFocusIntention(),
        netTimeSettings = loadNetTimeSettings(),
        onGoalApps = loadOnGoalApps(DEFAULT_GOAL_ID),
    )

    fun observe(observer: (DayPreferencesSnapshot) -> Unit): () -> Unit {
        observer(snapshot())
        return {}
    }

    val snapshots: Flow<DayPreferencesSnapshot>
        get() = callbackFlow {
            val stopObserving = observe { trySend(it) }
            awaitClose { stopObserving() }
        }

    suspend fun persist(snapshot: DayPreferencesSnapshot) {
        saveDayRange(snapshot.startMinutes, snapshot.endMinutes)
        saveShowSeconds(snapshot.showSeconds)
        saveSoundSettings(snapshot.soundSettings)
        saveGlobalGoal(snapshot.goalTitle, snapshot.goalDeadlineMillis, snapshot.goalStartMillis)
        savePomodoro(snapshot.pomodoroMinutes, snapshot.pomodoroEndMillis)
        saveFocusIntention(snapshot.focusIntention)
        saveNetTimeSettings(snapshot.netTimeSettings)
    }
}

object DefaultDayPreferences : DayPreferences {
    override fun loadStartMinutes(): Int = 8 * 60
    override fun loadEndMinutes(): Int = 18 * 60
    override fun saveDayRange(startMinutes: Int, endMinutes: Int) = Unit
    override fun loadShowSeconds(): Boolean = true
    override fun saveShowSeconds(showSeconds: Boolean) = Unit
    override fun loadSoundSettings(): SoundSettings = SoundSettings()
    override fun saveSoundSettings(settings: SoundSettings) = Unit
    override fun loadGoalTitle(): String = ""
    override fun loadGoalDeadlineMillis(): Long? = null
    override fun loadGoalStartMillis(): Long? = null
    override fun saveGlobalGoal(title: String, deadlineMillis: Long?, startMillis: Long?) = Unit
    override fun loadPomodoroMinutes(): Int = 25
    override fun loadPomodoroEndMillis(): Long? = null
    override fun savePomodoro(durationMinutes: Int, endMillis: Long?) = Unit
    override fun loadFocusIntention(): String = ""
    override fun saveFocusIntention(intention: String) = Unit
    override fun loadNetTimeSettings(): NetTimeSettings = NetTimeSettings()
    override fun saveNetTimeSettings(settings: NetTimeSettings) = Unit
}
