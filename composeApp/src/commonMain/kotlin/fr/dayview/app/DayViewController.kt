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

internal enum class SettingsCategory {
    DAY,
    DISPLAY,
    SOUNDS,
    NET_TIME,
    ON_GOAL,
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
    val detoursDayKey: Long = -1L,
    val detours: List<DetourEpisode> = emptyList(),
    val recentDetourMotifs: List<String> = emptyList(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val destination: DayViewDestination = DayViewDestination.TODAY,
    val settingsCategory: SettingsCategory? = null,
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

    /** Episodes of the current local day; stale storage from a previous day reads as empty. */
    val detoursToday: List<DetourEpisode>
        get() = if (detoursDayKey == dayKeyOf(dayNow)) detours else emptyList()

    val detourBodiesState: List<DetourBody>
        get() {
            val (start, end) = dayWindow
            return detourBodies(start, end, detoursToday)
        }

    val detourSourcesState: List<DetourSource>
        get() = detourSources(detoursToday)

    val detoursTotalToday: Duration
        get() = detoursTotal(detoursToday)
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
        state = state.copy(destination = DayViewDestination.SETTINGS, settingsCategory = null)
    }

    fun openSettingsCategory(category: SettingsCategory) {
        state = state.copy(settingsCategory = category)
    }

    fun closeSettingsCategory() {
        state = state.copy(settingsCategory = null)
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

    fun setThemeMode(mode: ThemeMode) {
        state = state.copy(themeMode = mode)
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

    /** Quick capture: the episode ends now and starts [durationMinutes] earlier. */
    fun addDetour(motif: String, durationMinutes: Int) {
        val clean = sanitizeDetourMotif(motif)
        if (clean.isEmpty()) return
        val end = state.now
        val windowStart = state.dayWindow.first
        var start = end - durationMinutes.coerceIn(1, 12 * 60).minutes
        if (start < windowStart && end > windowStart) start = windowStart
        commitDetours(state.detoursToday + DetourEpisode(start, end, clean), pushMotif = clean)
    }

    /** Retroactive add from the list editor; also feeds the suggestions. */
    fun addDetourEpisode(episode: DetourEpisode) {
        val clean = episode.copy(motif = sanitizeDetourMotif(episode.motif))
        if (clean.motif.isEmpty() || clean.end <= clean.start) return
        commitDetours(state.detoursToday + clean, pushMotif = clean.motif)
    }

    /** Replace the episode at [index] of [DayViewUiState.detoursToday]. */
    fun updateDetour(
        index: Int,
        episode: DetourEpisode,
    ) {
        val today = state.detoursToday
        if (index !in today.indices) return
        val clean = episode.copy(motif = sanitizeDetourMotif(episode.motif))
        if (clean.motif.isEmpty() || clean.end <= clean.start) return
        commitDetours(today.toMutableList().also { it[index] = clean })
    }

    fun removeDetour(index: Int) {
        val today = state.detoursToday
        if (index !in today.indices) return
        commitDetours(today.toMutableList().also { it.removeAt(index) })
    }

    /** Drop a motif from the recent-suggestions list; the day's episodes are untouched. */
    fun forgetRecentDetourMotif(motif: String) {
        val pruned = removeRecentDetourMotif(state.recentDetourMotifs, motif)
        if (pruned == state.recentDetourMotifs) return
        state = state.copy(recentDetourMotifs = pruned)
        persistState()
    }

    private fun commitDetours(
        episodes: List<DetourEpisode>,
        pushMotif: String? = null,
    ) {
        state = state.copy(
            detoursDayKey = dayKeyOf(state.now),
            detours = episodes.sortedBy { it.start },
            recentDetourMotifs = pushMotif
                ?.let { pushRecentDetourMotif(state.recentDetourMotifs, it) }
                ?: state.recentDetourMotifs,
        )
        persistState()
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
    detoursDayKey = detoursDayKey,
    detours = detours,
    recentDetourMotifs = recentDetourMotifs,
    themeMode = themeMode,
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
        detours = detours.map { it.copy(motif = sanitizeDetourMotif(it.motif)) },
        recentDetourMotifs = recentDetourMotifs.take(MAX_RECENT_DETOUR_MOTIFS),
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
        detoursDayKey = safe.detoursDayKey,
        detours = safe.detours,
        recentDetourMotifs = safe.recentDetourMotifs,
        themeMode = safe.themeMode,
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
        detoursDayKey = safe.detoursDayKey,
        detours = safe.detours,
        recentDetourMotifs = safe.recentDetourMotifs,
        themeMode = safe.themeMode,
        // Transient fields deliberately preserved: now, goalDeadlineText,
        // goalStartText, lastFocusClosure, destination, and calendar read results.
    )
}
