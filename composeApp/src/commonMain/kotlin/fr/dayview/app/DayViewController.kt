package fr.dayview.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Clock

internal enum class DayViewDestination {
    TODAY,
    SETTINGS,
}

internal data class DayViewUiState(
    val nowMillis: Long,
    val startMinutes: Int,
    val endMinutes: Int,
    val showSeconds: Boolean,
    val soundSettings: SoundSettings,
    val goalTitle: String,
    val goalDeadlineText: String,
    val goalDeadlineMillis: Long?,
    val goalStartText: String,
    val goalStartMillis: Long?,
    val pomodoroMinutes: Int,
    val pomodoroEndMillis: Long?,
    val focusIntention: String,
    val netTimeSettings: NetTimeSettings = NetTimeSettings(),
    val netCalendarPermission: Boolean = false,
    val availableCalendars: List<CalendarInfo> = emptyList(),
    val busyIntervals: List<BusyInterval> = emptyList(),
    val onGoalApps: Set<AppRef> = emptySet(),
    val focusPresenceIntervals: List<FocusPresenceInterval> = emptyList(),
    val lastFocusClosure: FocusClosureOutcome? = null,
    val destination: DayViewDestination = DayViewDestination.TODAY,
) {
    private val dayNowMillis: Long
        get() = if (showSeconds) nowMillis else nowMillis - nowMillis % 60_000L

    val dayProgress: DayProgress
        get() = calculateDayProgress(dayNowMillis, startMinutes, endMinutes)

    val pomodoroProgress: PomodoroProgress
        get() = calculatePomodoroProgress(nowMillis, pomodoroMinutes, pomodoroEndMillis)

    val focusIsActive: Boolean
        get() = pomodoroEndMillis?.let { it > nowMillis } == true

    /** Bornes absolues (millis) de la journée courante, pour la projection du temps net. */
    val dayWindow: Pair<Long, Long>
        get() = dayWindowMillis(dayNowMillis, startMinutes, endMinutes)

    val busyArcsState: List<BusyArc>
        get() = if (netTimeSettings.enabled) {
            val (start, end) = dayWindow
            busyArcs(start, end, busyIntervals)
        } else {
            emptyList()
        }

    val netTime: NetTime?
        get() = if (netTimeSettings.enabled) {
            val (start, end) = dayWindow
            calculateNetTime(dayProgress, dayNowMillis, start, end, busyIntervals)
        } else {
            null
        }

    val focusArcsState: List<FocusArc>
        get() {
            val (start, end) = dayWindow
            return focusArcs(start, end, focusPresenceIntervals)
        }

    val focusedTodayMillis: Long
        get() {
            val (start, end) = dayWindow
            return focusedMillis(start, end, focusPresenceIntervals)
        }
}

internal class DayViewController(
    private val preferences: DayPreferences,
    private val scope: CoroutineScope,
    initialNowMillis: Long = Clock.System.now().toEpochMilliseconds(),
) {
    var state: DayViewUiState by mutableStateOf(preferences.snapshot().toUiState(initialNowMillis))
        private set

    // Count of our own persists still running. While any is in flight, every
    // notification is necessarily an echo of one of them (the bridged persist()
    // applies fields one at a time and notifies after each), never a genuine
    // external change. A counter decremented in `finally` cannot be stranded by a
    // dropped/conflated echo or a failed persist, unlike matching individual echoes.
    private var selfWritesInFlight = 0

    private fun persistState() {
        val snapshot = state.toSnapshot()
        selfWritesInFlight++
        scope.launch {
            try {
                preferences.persist(snapshot)
            } finally {
                selfWritesInFlight--
            }
        }
    }

    init {
        // Goals created before goalStartMillis existed have a deadline but no
        // start, so their progress bar would never render. Backfill the start to
        // "now" (matching commitGoalDeadline) and persist it so progress accrues.
        if (state.goalDeadlineMillis != null && state.goalStartMillis == null) {
            val start = state.nowMillis
            state = state.copy(
                goalStartMillis = start,
                goalStartText = formatGoalDeadline(start),
            )
            persistState()
        }
    }

    fun tick(nowMillis: Long) {
        state = state.copy(nowMillis = nowMillis)
    }

    fun openSettings() {
        state = state.copy(destination = DayViewDestination.SETTINGS)
    }

    fun openToday() {
        state = state.copy(destination = DayViewDestination.TODAY)
    }

    fun setStartMinutes(minutes: Int) {
        val updated = minutes.coerceIn(0, state.endMinutes - 30)
        state = state.copy(startMinutes = updated)
        persistState()
    }

    fun setEndMinutes(minutes: Int) {
        val updated = minutes.coerceIn(state.startMinutes + 30, 23 * 60 + 59)
        state = state.copy(endMinutes = updated)
        persistState()
    }

    fun setShowSeconds(enabled: Boolean) {
        state = state.copy(showSeconds = enabled)
        persistState()
    }

    fun setSoundSettings(settings: SoundSettings) {
        val normalized = settings.normalized()
        state = state.copy(soundSettings = normalized)
        persistState()
    }

    fun setGoalTitle(value: String) {
        val updated = value.take(80)
        state = state.copy(goalTitle = updated)
        persistState()
    }

    fun setGoalDeadlineText(value: String) {
        state = state.copy(goalDeadlineText = value.take(16))
    }

    fun commitGoalDeadline() {
        val parsed = parseGoalDeadline(state.goalDeadlineText)
        if (parsed == null && state.goalDeadlineText.isNotBlank()) return
        val existingStart = state.goalStartMillis
        val start = when {
            parsed == null -> null
            existingStart == null || existingStart >= parsed -> state.nowMillis
            else -> existingStart
        }
        state = state.copy(
            goalDeadlineMillis = parsed,
            goalStartMillis = start,
            goalStartText = start?.let(::formatGoalDeadline).orEmpty(),
        )
        persistState()
    }

    fun setGoalStartText(value: String) {
        state = state.copy(goalStartText = value.take(16))
    }

    fun commitGoalStart() {
        val deadline = state.goalDeadlineMillis ?: return
        val parsed = parseGoalDeadline(state.goalStartText) ?: return
        if (parsed >= deadline) return
        state = state.copy(goalStartMillis = parsed)
        persistState()
    }

    fun setFocusIntention(value: String) {
        val updated = value.take(100)
        state = state.copy(focusIntention = updated, lastFocusClosure = null)
        persistState()
    }

    fun changePomodoroDuration(deltaMinutes: Int) {
        if (state.pomodoroProgress.status == PomodoroStatus.ACTIVE) return
        val updated = (state.pomodoroMinutes + deltaMinutes).coerceIn(5, 180)
        state = state.copy(pomodoroMinutes = updated, pomodoroEndMillis = null)
        persistState()
    }

    fun startPomodoro() {
        if (state.focusIntention.isBlank()) return
        val endMillis = state.nowMillis + state.pomodoroMinutes * 60_000L
        state = state.copy(pomodoroEndMillis = endMillis, lastFocusClosure = null)
        persistState()
    }

    fun stopPomodoro() {
        state = state.copy(pomodoroEndMillis = null)
        persistState()
    }

    fun closePomodoro(outcome: FocusClosureOutcome) {
        val updatedIntention = focusIntentionAfterClosure(state.focusIntention, outcome)
        // Single atomic persist of the whole snapshot: unlike the previous
        // two-save version, there is no intermediate state to reconcile against.
        state = state.copy(
            pomodoroEndMillis = null,
            focusIntention = updatedIntention,
            lastFocusClosure = outcome,
        )
        persistState()
    }

    fun setNetTimeSettings(settings: NetTimeSettings) {
        state = state.copy(netTimeSettings = settings)
        persistState()
    }

    fun setOnGoalApps(apps: Set<AppRef>) {
        state = state.copy(onGoalApps = apps)
        preferences.saveOnGoalApps(DEFAULT_GOAL_ID, apps)
    }

    fun setFocusPresenceIntervals(intervals: List<FocusPresenceInterval>) {
        state = state.copy(focusPresenceIntervals = intervals)
    }

    /** Injecte le résultat d'une lecture calendrier (hors thread UI). */
    fun updateNetTimeData(
        hasPermission: Boolean,
        busyIntervals: List<BusyInterval>,
        availableCalendars: List<CalendarInfo>,
    ) {
        state = state.copy(
            netCalendarPermission = hasPermission,
            busyIntervals = busyIntervals,
            availableCalendars = availableCalendars,
        )
    }

    fun onPreferencesChanged(snapshot: DayPreferencesSnapshot) {
        // A genuine external write that lands while one of our own writes is in
        // flight is missed until the next change; acceptable because external
        // writers (tile/widget/alarm) are rare and writes complete quickly.
        if (selfWritesInFlight > 0) return
        state = state.withPersisted(snapshot)
    }
}

private fun DayViewUiState.toSnapshot(): DayPreferencesSnapshot = DayPreferencesSnapshot(
    startMinutes = startMinutes,
    endMinutes = endMinutes,
    showSeconds = showSeconds,
    soundSettings = soundSettings,
    goalTitle = goalTitle,
    goalDeadlineMillis = goalDeadlineMillis,
    goalStartMillis = goalStartMillis,
    pomodoroMinutes = pomodoroMinutes,
    pomodoroEndMillis = pomodoroEndMillis,
    focusIntention = focusIntention,
    netTimeSettings = netTimeSettings,
).coerced()

private fun DayPreferencesSnapshot.coerced(): DayPreferencesSnapshot {
    val safeStart = startMinutes.coerceIn(0, 23 * 60 + 29)
    val safeEnd = endMinutes.coerceIn(safeStart + 30, 23 * 60 + 59)
    return copy(
        startMinutes = safeStart,
        endMinutes = safeEnd,
        soundSettings = soundSettings.normalized(),
        goalTitle = goalTitle.take(80),
        pomodoroMinutes = pomodoroMinutes.coerceIn(5, 180),
        focusIntention = focusIntention.take(100),
    )
}

private fun DayPreferencesSnapshot.toUiState(nowMillis: Long): DayViewUiState {
    val safe = coerced()
    return DayViewUiState(
        nowMillis = nowMillis,
        startMinutes = safe.startMinutes,
        endMinutes = safe.endMinutes,
        showSeconds = safe.showSeconds,
        soundSettings = safe.soundSettings,
        goalTitle = safe.goalTitle,
        goalDeadlineText = safe.goalDeadlineMillis?.let(::formatGoalDeadline).orEmpty(),
        goalDeadlineMillis = safe.goalDeadlineMillis,
        goalStartText = safe.goalStartMillis?.let(::formatGoalDeadline).orEmpty(),
        goalStartMillis = safe.goalStartMillis,
        pomodoroMinutes = safe.pomodoroMinutes,
        pomodoroEndMillis = safe.pomodoroEndMillis,
        focusIntention = safe.focusIntention,
        netTimeSettings = safe.netTimeSettings,
        onGoalApps = safe.onGoalApps,
    )
}

private fun DayViewUiState.withPersisted(snapshot: DayPreferencesSnapshot): DayViewUiState {
    val safe = snapshot.coerced()
    return copy(
        startMinutes = safe.startMinutes,
        endMinutes = safe.endMinutes,
        showSeconds = safe.showSeconds,
        soundSettings = safe.soundSettings,
        goalTitle = safe.goalTitle,
        goalDeadlineMillis = safe.goalDeadlineMillis,
        goalStartMillis = safe.goalStartMillis,
        pomodoroMinutes = safe.pomodoroMinutes,
        pomodoroEndMillis = safe.pomodoroEndMillis,
        focusIntention = safe.focusIntention,
        netTimeSettings = safe.netTimeSettings,
        onGoalApps = safe.onGoalApps,
        // Transient fields deliberately preserved: nowMillis, goalDeadlineText,
        // goalStartText, lastFocusClosure, destination, and calendar read results.
    )
}
