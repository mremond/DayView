package fr.dayview.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.weekday_fri
import fr.dayview.app.generated.resources.weekday_mon
import fr.dayview.app.generated.resources.weekday_sat
import fr.dayview.app.generated.resources.weekday_sun
import fr.dayview.app.generated.resources.weekday_thu
import fr.dayview.app.generated.resources.weekday_tue
import fr.dayview.app.generated.resources.weekday_wed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal enum class DayViewDestination {
    TODAY,
    SETTINGS,
    HISTORY,
}

/** Monday→Sunday short weekday labels, in the same order as [weekDaysEndingAt]. */
private val weekdayLabelResources: List<StringResource> = listOf(
    Res.string.weekday_mon,
    Res.string.weekday_tue,
    Res.string.weekday_wed,
    Res.string.weekday_thu,
    Res.string.weekday_fri,
    Res.string.weekday_sat,
    Res.string.weekday_sun,
)

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
    val busyDayKey: Long = -1L,
    val availableCalendars: List<CalendarInfo> = emptyList(),
    val busyIntervals: List<BusyInterval> = emptyList(),
    val onGoalApps: Set<AppRef> = emptySet(),
    val focusPresenceIntervals: List<FocusPresenceInterval> = emptyList(),
    val lastFocusClosure: FocusClosureOutcome? = null,
    val detoursDayKey: Long = -1L,
    val detours: List<DetourEpisode> = emptyList(),
    val recentDetourCategories: List<String> = emptyList(),
    val plannedObligationsDayKey: Long = -1L,
    val plannedObligations: List<String> = emptyList(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val cleanSessions: CleanSessionLedger = CleanSessionLedger(),
    val sessionOffGoal: Duration = Duration.ZERO,
    val fontScale: Float = 1.0f,
    val destination: DayViewDestination = DayViewDestination.TODAY,
    val settingsCategory: SettingsCategory? = null,
    val selectedHistoryDay: Long? = null,
    val historyWeek: List<HistoryWeekDay> = emptyList(),
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

    /** Busy layer of the current local day; stale storage from a previous day reads as empty. */
    val busyIntervalsToday: List<BusyInterval>
        get() = if (busyDayKey == dayKeyOf(dayNow)) busyIntervals else emptyList()

    /** Calendars of the current local day; stale storage from a previous day reads as empty. */
    val availableCalendarsToday: List<CalendarInfo>
        get() = if (busyDayKey == dayKeyOf(dayNow)) availableCalendars else emptyList()

    private val calendarNamesById: Map<String, String>
        get() = availableCalendarsToday.associate { it.id to it.displayName }

    val busyBlockArcsState: List<BusyBlockArc>
        get() = if (netTimeSettings.enabled) {
            val (start, end) = dayWindow
            busyBlockArcs(start, end, busyIntervalsToday, calendarNamesById)
        } else {
            emptyList()
        }

    val netTime: NetTime?
        get() = if (netTimeSettings.enabled) {
            val (start, end) = dayWindow
            calculateNetTime(dayProgress, dayNow, start, end, busyIntervalsToday.filterNot { it.isFocusBlock() })
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

    /** The day's must-do motifs; stale storage from a previous day reads as empty. */
    val plannedObligationsToday: List<String>
        get() = if (plannedObligationsDayKey == dayKeyOf(dayNow)) plannedObligations else emptyList()

    val detourBodiesState: List<DetourBody>
        get() {
            val (start, end) = dayWindow
            return detourBodies(start, end, detoursToday)
        }

    val detourSourcesState: List<DetourSource>
        get() = detourSources(detoursToday)

    val detoursTotalToday: Duration
        get() = detoursTotal(detoursToday)

    val detoursOffWindowTotalToday: Duration
        get() {
            val (start, end) = dayWindow
            return offWindowDetoursTotal(start, end, detoursToday)
        }

    val cleanSessionsToday: Int
        get() = if (cleanSessions.dayKey == dayKeyOf(dayNow)) cleanSessions.cleanToday else 0

    val cleanStreakDays: Int
        get() = displayedStreak(cleanSessions, dayKeyOf(dayNow))
}

internal class DayViewController(
    private val preferences: DayPreferences,
    private val scope: CoroutineScope,
    initialSnapshot: DayPreferencesSnapshot,
    initialNow: Instant = Clock.System.now(),
    private val history: DayHistoryStore = InMemoryDayHistoryStore(),
    initialFocusPresenceIntervals: List<FocusPresenceInterval> = emptyList(),
) {
    // Focus presence is desktop-only and persisted outside the shared snapshot
    // (DesktopPreferences.loadFocusPresence). Seed it into the initial state so the
    // synchronous init archival below captures the previous day's presence too; the live
    // ticker overwrites it via setFocusPresenceIntervals once composition starts.
    var state: DayViewUiState by mutableStateOf(
        initialSnapshot.toUiState(initialNow).copy(focusPresenceIntervals = initialFocusPresenceIntervals),
    )
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
        maybeArchivePreviousDay()
    }

    /** The day the persisted day-scoped fields (detours, clean-session ledger, calendar busy) belong to. */
    private fun persistedDayKey(state: DayViewUiState): Long? = listOf(state.detoursDayKey, state.cleanSessions.dayKey, state.busyDayKey).filter { it != -1L }.maxOrNull()

    /**
     * Archives the previous day's ring before its day-scoped data is discarded on
     * rollover. `write` is idempotent, so calling this more than once for the same
     * stale day is harmless.
     */
    private fun maybeArchivePreviousDay() {
        val key = persistedDayKey(state) ?: return
        if (key == dayKeyOf(state.now)) return
        val record = state.toHistoryRecord(key)
        scope.launch { history.write(record) }
    }

    fun tick(now: Instant) {
        val dayChanged = dayKeyOf(now) != dayKeyOf(state.now)
        state = state.copy(now = now)
        if (dayChanged) maybeArchivePreviousDay()
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

    /** Switches to the history week overview and (asynchronously) loads its records. */
    fun openHistory() {
        val todayKey = dayKeyOf(state.now)
        val keys = weekDaysEndingAt(todayKey)
        state = state.copy(destination = DayViewDestination.HISTORY, selectedHistoryDay = null)
        scope.launch {
            val present = history.listDays(keys.first()..keys.last()).toSet()
            val labels = weekdayLabelResources.map { getString(it) }
            // Today is never archived (maybeArchivePreviousDay skips the current day), so its
            // stored cell is always null. Build it from the live state so today renders a real,
            // clickable ring instead of a greyed placeholder.
            val todayRecord = state.toHistoryRecord(todayKey)
            val days = keys.mapIndexed { i, key ->
                val record = when {
                    key == todayKey -> todayRecord
                    key in present -> history.read(key)
                    else -> null
                }
                HistoryWeekDay(key, labels[i], record)
            }
            state = state.copy(historyWeek = days)
        }
    }

    fun openHistoryDay(dayKey: Long) {
        state = state.copy(selectedHistoryDay = dayKey)
    }

    /** Day → week → today: closes the open day first, then leaves the week overview. */
    fun closeHistory() {
        state = if (state.selectedHistoryDay != null) {
            state.copy(selectedHistoryDay = null)
        } else {
            state.copy(destination = DayViewDestination.TODAY)
        }
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

    fun setFontScale(scale: Float) {
        state = state.copy(fontScale = scale.coerceIn(1.0f, 1.5f))
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
        val ledger = closedFocusLedger(
            cleanSessions = state.cleanSessions,
            dayKey = dayKeyOf(state.now),
            pomodoroEnd = state.pomodoroEnd,
            pomodoroMinutes = state.pomodoroMinutes,
            sessionOffGoal = state.sessionOffGoal,
            detoursToday = state.detoursToday,
            outcome = outcome,
        )
        // Single atomic persist of the whole snapshot: unlike the previous
        // two-save version, there is no intermediate state to reconcile against.
        state = state.copy(
            pomodoroEnd = null,
            focusIntention = updatedIntention,
            lastFocusClosure = outcome,
            cleanSessions = ledger,
            sessionOffGoal = Duration.ZERO,
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

    fun setSessionOffGoal(duration: Duration) {
        state = state.copy(sessionOffGoal = duration)
    }

    /** Quick capture: the episode ends now and starts [durationMinutes] earlier. */
    fun addDetour(
        category: String,
        durationMinutes: Int,
        description: String = "",
    ) {
        val clean = sanitizeDetourCategory(category)
        if (clean.isEmpty()) return
        val end = state.now
        // Keep the full declared span; only floor at the start of the local day so a very long
        // capture cannot cross into yesterday and break the day-scoped, time-only list display.
        val start = maxOf(end - durationMinutes.coerceIn(1, 12 * 60).minutes, startOfLocalDay(end))
        commitDetours(
            state.detoursToday + DetourEpisode(start, end, clean, sanitizeDetourDescription(description)),
            pushCategory = clean,
        )
    }

    /** Retroactive add from the list editor; also feeds the suggestions. */
    fun addDetourEpisode(episode: DetourEpisode) {
        val clean = episode.copy(
            category = sanitizeDetourCategory(episode.category),
            description = sanitizeDetourDescription(episode.description),
        )
        if (clean.category.isEmpty() || clean.end <= clean.start) return
        commitDetours(state.detoursToday + clean, pushCategory = clean.category)
    }

    /** Replace the episode at [index] of [DayViewUiState.detoursToday]. */
    fun updateDetour(
        index: Int,
        episode: DetourEpisode,
    ) {
        val today = state.detoursToday
        if (index !in today.indices) return
        val clean = episode.copy(
            category = sanitizeDetourCategory(episode.category),
            description = sanitizeDetourDescription(episode.description),
        )
        if (clean.category.isEmpty() || clean.end <= clean.start) return
        commitDetours(today.toMutableList().also { it[index] = clean })
    }

    fun removeDetour(index: Int) {
        val today = state.detoursToday
        if (index !in today.indices) return
        commitDetours(today.toMutableList().also { it.removeAt(index) })
    }

    /** Drop a category from the recent-suggestions list; the day's episodes are untouched. */
    fun forgetRecentDetourCategory(category: String) {
        val pruned = removeRecentDetourCategory(state.recentDetourCategories, category)
        if (pruned == state.recentDetourCategories) return
        state = state.copy(recentDetourCategories = pruned)
        persistState()
    }

    private fun commitDetours(
        episodes: List<DetourEpisode>,
        pushCategory: String? = null,
    ) {
        state = state.copy(
            detoursDayKey = dayKeyOf(state.now),
            detours = episodes.sortedBy { it.start },
            recentDetourCategories = pushCategory
                ?.let { pushRecentDetourCategory(state.recentDetourCategories, it) }
                ?: state.recentDetourCategories,
        )
        persistState()
    }

    fun addPlannedObligation(motif: String) {
        commitPlannedObligations(addPlannedObligation(state.plannedObligationsToday, motif))
    }

    fun removePlannedObligation(motif: String) {
        commitPlannedObligations(removePlannedObligation(state.plannedObligationsToday, motif))
    }

    fun completePlannedObligation(
        originalObligation: String,
        detourCategory: String,
        description: String,
        durationMinutes: Int,
        startMinutesOfDay: Int?,
    ) {
        if (startMinutesOfDay == null) {
            addDetour(detourCategory, durationMinutes, description)
        } else {
            addDetourEpisode(
                detourEpisodeAt(state.now, startMinutesOfDay, durationMinutes, detourCategory, description),
            )
        }
        removePlannedObligation(originalObligation)
    }

    private fun commitPlannedObligations(obligations: List<String>) {
        state = state.copy(
            plannedObligationsDayKey = dayKeyOf(state.now),
            plannedObligations = obligations,
        )
        persistState()
    }

    /** Injecte le résultat d'une lecture calendrier (hors thread UI). */
    fun updateNetTimeData(
        hasPermission: Boolean,
        busyIntervals: List<BusyInterval>,
        availableCalendars: List<CalendarInfo>,
    ) {
        // The day's calendar-busy layer is transient in-memory data; persist it (day-tagged)
        // so a cold launch on the next day archives a faithful ring (see maybeArchivePreviousDay).
        // Day-tag and persist only when there is an actual busy layer, mirroring how detours and
        // clean-sessions tag their day on real activity: this keeps empty days out of history and
        // avoids a per-minute write when the calendar read (re-run every minute) is unchanged.
        val hasBusyLayer = busyIntervals.isNotEmpty()
        val dayKey = if (hasBusyLayer) dayKeyOf(state.now) else state.busyDayKey
        val changed = hasBusyLayer &&
            (
                state.busyIntervals != busyIntervals ||
                    state.availableCalendars != availableCalendars ||
                    state.busyDayKey != dayKey
                )
        state = state.copy(
            netCalendarPermission = hasPermission,
            busyDayKey = dayKey,
            busyIntervals = busyIntervals,
            availableCalendars = availableCalendars,
        )
        if (changed) persistState()
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
    busyDayKey = busyDayKey,
    busyIntervals = busyIntervals,
    availableCalendars = availableCalendars,
    detoursDayKey = detoursDayKey,
    detours = detours,
    recentDetourCategories = recentDetourCategories,
    plannedObligationsDayKey = plannedObligationsDayKey,
    plannedObligations = plannedObligations,
    themeMode = themeMode,
    cleanSessions = cleanSessions,
    fontScale = fontScale,
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
        detours = detours.map { it.copy(category = sanitizeDetourCategory(it.category)) },
        recentDetourCategories = recentDetourCategories.take(MAX_RECENT_DETOUR_CATEGORIES),
        plannedObligations = plannedObligations.map { sanitizeLabel(it, 60) }
            .filter { it.isNotEmpty() }
            .take(MAX_PLANNED_OBLIGATIONS),
        cleanSessions = cleanSessions.copy(
            cleanToday = cleanSessions.cleanToday.coerceAtLeast(0),
            streakDays = cleanSessions.streakDays.coerceAtLeast(0),
        ),
        fontScale = fontScale.coerceIn(1.0f, 1.5f),
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
        busyDayKey = safe.busyDayKey,
        busyIntervals = safe.busyIntervals,
        availableCalendars = safe.availableCalendars,
        detoursDayKey = safe.detoursDayKey,
        detours = safe.detours,
        recentDetourCategories = safe.recentDetourCategories,
        plannedObligationsDayKey = safe.plannedObligationsDayKey,
        plannedObligations = safe.plannedObligations,
        themeMode = safe.themeMode,
        cleanSessions = safe.cleanSessions,
        fontScale = safe.fontScale,
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
        recentDetourCategories = safe.recentDetourCategories,
        plannedObligationsDayKey = safe.plannedObligationsDayKey,
        plannedObligations = safe.plannedObligations,
        themeMode = safe.themeMode,
        cleanSessions = safe.cleanSessions,
        fontScale = safe.fontScale,
        // Transient fields deliberately preserved: now, goalDeadlineText,
        // goalStartText, lastFocusClosure, sessionOffGoal, destination, and
        // calendar read results.
    )
}
