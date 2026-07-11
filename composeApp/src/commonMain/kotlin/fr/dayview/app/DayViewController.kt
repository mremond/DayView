package fr.dayview.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal enum class DayViewDestination {
    TODAY,
    SETTINGS,
}

internal data class DayViewUiState(
    val now: Instant,
    val startMinutes: Int,
    val endMinutes: Int,
    val showSeconds: Boolean,
    val soundSettings: SoundSettings,
    val goalTitle: String,
    val goalDeadlineText: String,
    val goalDeadline: Instant?,
    val goalStartText: String,
    val goalStart: Instant?,
    val pomodoroMinutes: Int,
    val pomodoroEnd: Instant?,
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
    private val dayNow: Instant
        get() = if (showSeconds) now else now - (now.toEpochMilliseconds() % 60_000L).milliseconds

    val dayProgress: DayProgress
        get() = calculateDayProgress(dayNow, startMinutes, endMinutes)

    val pomodoroProgress: PomodoroProgress
        get() = calculatePomodoroProgress(now, pomodoroMinutes, pomodoroEnd)

    val focusIsActive: Boolean
        get() = pomodoroEnd?.let { it > now } == true

    /** Bornes absolues de la journée courante, pour la projection du temps net. */
    val dayWindow: Pair<Instant, Instant>
        get() = dayWindow(dayNow, startMinutes, endMinutes)

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
            calculateNetTime(dayProgress, dayNow, start, end, busyIntervals)
        } else {
            null
        }

    val focusArcsState: List<FocusArc>
        get() {
            val (start, end) = dayWindow
            return focusArcs(start, end, focusPresenceIntervals)
        }

    val focusedToday: Duration
        get() {
            val (start, end) = dayWindow
            return focusedTime(start, end, focusPresenceIntervals)
        }
}

internal class DayViewController(
    private val preferences: DayPreferences,
    private val scope: CoroutineScope,
    initialSnapshot: DayPreferencesSnapshot,
    initialNow: Instant = Clock.System.now(),
) {
    var state: DayViewUiState by mutableStateOf(initialSnapshot.toUiState(initialNow))
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
        // Goals created before goalStart existed have a deadline but no start, so
        // their progress bar would never render. Backfill the start to "now"
        // (matching commitGoalDeadline) and persist it so progress accrues.
        if (state.goalDeadline != null && state.goalStart == null) {
            val start = state.now
            state = state.copy(
                goalStart = start,
                goalStartText = formatGoalDeadline(start),
            )
            persistState()
        }
    }

    fun tick(now: Instant) {
        state = state.copy(now = now)
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
        val existingStart = state.goalStart
        val start = when {
            parsed == null -> null
            existingStart == null || existingStart >= parsed -> state.now
            else -> existingStart
        }
        state = state.copy(
            goalDeadline = parsed,
            goalStart = start,
            goalStartText = start?.let(::formatGoalDeadline).orEmpty(),
        )
        persistState()
    }

    fun setGoalStartText(value: String) {
        state = state.copy(goalStartText = value.take(16))
    }

    fun commitGoalStart() {
        val deadline = state.goalDeadline ?: return
        val parsed = parseGoalDeadline(state.goalStartText) ?: return
        if (parsed >= deadline) return
        state = state.copy(goalStart = parsed)
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
        state = state.copy(pomodoroMinutes = updated, pomodoroEnd = null)
        persistState()
    }

    fun startPomodoro() {
        if (state.focusIntention.isBlank()) return
        val end = state.now + state.pomodoroMinutes.minutes
        state = state.copy(pomodoroEnd = end, lastFocusClosure = null)
        persistState()
    }

    fun stopPomodoro() {
        state = state.copy(pomodoroEnd = null)
        persistState()
    }

    fun closePomodoro(outcome: FocusClosureOutcome) {
        val updatedIntention = focusIntentionAfterClosure(state.focusIntention, outcome)
        // Single atomic persist of the whole snapshot: unlike the previous
        // two-save version, there is no intermediate state to reconcile against.
        state = state.copy(
            pomodoroEnd = null,
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
        persistState()
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
    goalDeadline = goalDeadline,
    goalStart = goalStart,
    pomodoroMinutes = pomodoroMinutes,
    pomodoroEnd = pomodoroEnd,
    focusIntention = focusIntention,
    netTimeSettings = netTimeSettings,
    onGoalApps = onGoalApps,
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

private fun DayPreferencesSnapshot.toUiState(now: Instant): DayViewUiState {
    val safe = coerced()
    return DayViewUiState(
        now = now,
        startMinutes = safe.startMinutes,
        endMinutes = safe.endMinutes,
        showSeconds = safe.showSeconds,
        soundSettings = safe.soundSettings,
        goalTitle = safe.goalTitle,
        goalDeadlineText = safe.goalDeadline?.let(::formatGoalDeadline).orEmpty(),
        goalDeadline = safe.goalDeadline,
        goalStartText = safe.goalStart?.let(::formatGoalDeadline).orEmpty(),
        goalStart = safe.goalStart,
        pomodoroMinutes = safe.pomodoroMinutes,
        pomodoroEnd = safe.pomodoroEnd,
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
        goalDeadline = safe.goalDeadline,
        goalStart = safe.goalStart,
        pomodoroMinutes = safe.pomodoroMinutes,
        pomodoroEnd = safe.pomodoroEnd,
        focusIntention = safe.focusIntention,
        netTimeSettings = safe.netTimeSettings,
        onGoalApps = safe.onGoalApps,
        // Transient fields deliberately preserved: now, goalDeadlineText,
        // goalStartText, lastFocusClosure, destination, and calendar read results.
    )
}
