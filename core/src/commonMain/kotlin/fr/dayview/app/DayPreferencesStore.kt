package fr.dayview.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

internal object DayPreferenceKeys {
    const val START = "start_minutes"
    const val END = "end_minutes"
    const val SHOW_SECONDS = "show_seconds"
    const val SOUND_ENABLED = "sound_enabled"
    const val SOUND_START = "sound_start"
    const val SOUND_INTERVAL = "sound_interval"
    const val SOUND_END = "sound_end"
    const val SOUND_INTERVAL_MINUTES = "sound_interval_minutes"
    const val SOUND_VOLUME = "sound_volume"
    const val GOAL_TITLE = "goal_title"
    const val GOAL_DEADLINE = "goal_deadline"
    const val GOAL_START = "goal_start"
    const val POMODORO_MINUTES = "pomodoro_minutes"
    const val POMODORO_END = "pomodoro_end"
    const val OPEN_DETOUR_START = "detour_open_start"
    const val OPEN_DETOUR_CATEGORY = "detour_open_category"
    const val OPEN_DETOUR_DESCRIPTION = "detour_open_description"
    const val FOCUS_INTENTION = "focus_intention"
    const val NET_TIME_ENABLED = "net_time_enabled"
    const val NET_TIME_CALENDARS = "net_time_calendars"
    const val BUSY_DAY = "busy_day"
    const val BUSY_INTERVALS = "busy_intervals"
    const val AVAILABLE_CALENDARS = "available_calendars"
    const val ON_GOAL_APPS = "on_goal_apps"
    const val DETOURS_DAY = "detours_day"
    const val DETOURS = "detours"

    // Keep the VALUE "detour_recent_motifs" for back-compat with stored data.
    const val DETOUR_RECENT_CATEGORIES = "detour_recent_motifs"
    const val PLANNED_OBLIGATIONS_DAY = "planned_obligations_day"
    const val PLANNED_OBLIGATIONS = "planned_obligations"
    const val PLANNED_OBLIGATIONS_COMPLETED = "planned_obligations_completed"
    const val THEME_MODE = "theme_mode"
    const val CLEAN_SESSIONS_DAY = "clean_sessions_day"
    const val CLEAN_SESSIONS_TODAY = "clean_sessions_today"
    const val CLEAN_STREAK_DAYS = "clean_streak_days"
    const val CLEAN_STREAK_LAST_DAY = "clean_streak_last_day"
    const val FONT_SCALE = "font_scale"
    const val NO_DEADLINE = -1L
}

private val startKey = intPreferencesKey(DayPreferenceKeys.START)
private val endKey = intPreferencesKey(DayPreferenceKeys.END)
private val showSecondsKey = booleanPreferencesKey(DayPreferenceKeys.SHOW_SECONDS)
private val soundEnabledKey = booleanPreferencesKey(DayPreferenceKeys.SOUND_ENABLED)
private val soundStartKey = booleanPreferencesKey(DayPreferenceKeys.SOUND_START)
private val soundIntervalKey = booleanPreferencesKey(DayPreferenceKeys.SOUND_INTERVAL)
private val soundEndKey = booleanPreferencesKey(DayPreferenceKeys.SOUND_END)
private val soundIntervalMinutesKey = intPreferencesKey(DayPreferenceKeys.SOUND_INTERVAL_MINUTES)
private val soundVolumeKey = intPreferencesKey(DayPreferenceKeys.SOUND_VOLUME)
private val goalTitleKey = stringPreferencesKey(DayPreferenceKeys.GOAL_TITLE)
private val goalDeadlineKey = longPreferencesKey(DayPreferenceKeys.GOAL_DEADLINE)
private val goalStartKey = longPreferencesKey(DayPreferenceKeys.GOAL_START)
private val pomodoroMinutesKey = intPreferencesKey(DayPreferenceKeys.POMODORO_MINUTES)
private val pomodoroEndKey = longPreferencesKey(DayPreferenceKeys.POMODORO_END)
private val openDetourStartKey = longPreferencesKey(DayPreferenceKeys.OPEN_DETOUR_START)
private val openDetourCategoryKey = stringPreferencesKey(DayPreferenceKeys.OPEN_DETOUR_CATEGORY)
private val openDetourDescriptionKey = stringPreferencesKey(DayPreferenceKeys.OPEN_DETOUR_DESCRIPTION)
private val focusIntentionKey = stringPreferencesKey(DayPreferenceKeys.FOCUS_INTENTION)
private val netTimeEnabledKey = booleanPreferencesKey(DayPreferenceKeys.NET_TIME_ENABLED)
private val netTimeCalendarsKey = stringPreferencesKey(DayPreferenceKeys.NET_TIME_CALENDARS)
private val busyDayPrefKey = longPreferencesKey(DayPreferenceKeys.BUSY_DAY)
private val busyIntervalsKey = stringPreferencesKey(DayPreferenceKeys.BUSY_INTERVALS)
private val availableCalendarsKey = stringPreferencesKey(DayPreferenceKeys.AVAILABLE_CALENDARS)
private val onGoalAppsKey = stringPreferencesKey(DayPreferenceKeys.ON_GOAL_APPS)
private val detoursDayPrefKey = longPreferencesKey(DayPreferenceKeys.DETOURS_DAY)
private val detoursKey = stringPreferencesKey(DayPreferenceKeys.DETOURS)
private val detourRecentCategoriesKey = stringPreferencesKey(DayPreferenceKeys.DETOUR_RECENT_CATEGORIES)
private val plannedObligationsDayPrefKey = longPreferencesKey(DayPreferenceKeys.PLANNED_OBLIGATIONS_DAY)
private val plannedObligationsKey = stringPreferencesKey(DayPreferenceKeys.PLANNED_OBLIGATIONS)
private val plannedObligationsCompletedKey = stringPreferencesKey(DayPreferenceKeys.PLANNED_OBLIGATIONS_COMPLETED)
private val themeModeKey = stringPreferencesKey(DayPreferenceKeys.THEME_MODE)
private val cleanSessionsDayKey = longPreferencesKey(DayPreferenceKeys.CLEAN_SESSIONS_DAY)
private val cleanSessionsTodayKey = intPreferencesKey(DayPreferenceKeys.CLEAN_SESSIONS_TODAY)
private val cleanStreakDaysKey = intPreferencesKey(DayPreferenceKeys.CLEAN_STREAK_DAYS)
private val cleanStreakLastDayKey = longPreferencesKey(DayPreferenceKeys.CLEAN_STREAK_LAST_DAY)
private val fontScaleKey = floatPreferencesKey(DayPreferenceKeys.FONT_SCALE)

class DayPreferencesStore(
    private val dataStore: DataStore<Preferences>,
) : DayPreferences {
    override val snapshots: Flow<DayPreferencesSnapshot> = dataStore.data.map { it.toSnapshot() }

    override suspend fun persist(snapshot: DayPreferencesSnapshot) {
        dataStore.edit { prefs ->
            prefs[startKey] = snapshot.startMinutes
            prefs[endKey] = snapshot.endMinutes
            prefs[showSecondsKey] = snapshot.showSeconds
            prefs[soundEnabledKey] = snapshot.soundSettings.enabled
            prefs[soundStartKey] = snapshot.soundSettings.startCueEnabled
            prefs[soundIntervalKey] = snapshot.soundSettings.intervalCueEnabled
            prefs[soundEndKey] = snapshot.soundSettings.endCueEnabled
            prefs[soundIntervalMinutesKey] = snapshot.soundSettings.intervalMinutes
            prefs[soundVolumeKey] = snapshot.soundSettings.volumePercent
            prefs[goalTitleKey] = snapshot.goalTitle
            prefs[goalDeadlineKey] = snapshot.goalDeadline?.toEpochMilliseconds() ?: DayPreferenceKeys.NO_DEADLINE
            prefs[goalStartKey] = snapshot.goalStart?.toEpochMilliseconds() ?: DayPreferenceKeys.NO_DEADLINE
            prefs[pomodoroMinutesKey] = snapshot.pomodoroMinutes
            prefs[pomodoroEndKey] = snapshot.pomodoroEnd?.toEpochMilliseconds() ?: DayPreferenceKeys.NO_DEADLINE
            prefs[openDetourStartKey] = snapshot.openDetourStart?.toEpochMilliseconds() ?: DayPreferenceKeys.NO_DEADLINE
            prefs[openDetourCategoryKey] = snapshot.openDetourCategory
            prefs[openDetourDescriptionKey] = snapshot.openDetourDescription
            prefs[focusIntentionKey] = snapshot.focusIntention
            prefs[netTimeEnabledKey] = snapshot.netTimeSettings.enabled
            prefs[netTimeCalendarsKey] = snapshot.netTimeSettings.includedCalendarIds.joinToString("\n")
            prefs[busyDayPrefKey] = snapshot.busyDayKey
            prefs[busyIntervalsKey] = encodeBusyIntervals(snapshot.busyIntervals)
            prefs[availableCalendarsKey] = encodeCalendarNames(snapshot.availableCalendars.associate { it.id to it.displayName })
            prefs[onGoalAppsKey] = encodeAppRefs(snapshot.onGoalApps)
            prefs[detoursDayPrefKey] = snapshot.detoursDayKey
            prefs[detoursKey] = encodeDetours(snapshot.detours)
            prefs[detourRecentCategoriesKey] = encodeRecentDetourCategories(snapshot.recentDetourCategories)
            prefs[plannedObligationsDayPrefKey] = snapshot.plannedObligationsDayKey
            prefs[plannedObligationsKey] = encodePlannedObligations(snapshot.plannedObligations)
            prefs[plannedObligationsCompletedKey] = encodePlannedObligations(snapshot.plannedObligationsCompleted)
            prefs[themeModeKey] = snapshot.themeMode.name
            prefs[cleanSessionsDayKey] = snapshot.cleanSessions.dayKey
            prefs[cleanSessionsTodayKey] = snapshot.cleanSessions.cleanToday
            prefs[cleanStreakDaysKey] = snapshot.cleanSessions.streakDays
            prefs[cleanStreakLastDayKey] = snapshot.cleanSessions.streakLastDayKey
            prefs[fontScaleKey] = snapshot.fontScale
        }
    }
}

private fun Preferences.toSnapshot(): DayPreferencesSnapshot {
    val defaults = DayPreferencesSnapshot()
    return DayPreferencesSnapshot(
        startMinutes = this[startKey] ?: defaults.startMinutes,
        endMinutes = this[endKey] ?: defaults.endMinutes,
        showSeconds = this[showSecondsKey] ?: defaults.showSeconds,
        soundSettings = SoundSettings(
            enabled = this[soundEnabledKey] ?: false,
            startCueEnabled = this[soundStartKey] ?: true,
            intervalCueEnabled = this[soundIntervalKey] ?: true,
            endCueEnabled = this[soundEndKey] ?: true,
            intervalMinutes = this[soundIntervalMinutesKey] ?: 30,
            volumePercent = this[soundVolumeKey] ?: 40,
        ),
        goalTitle = this[goalTitleKey] ?: defaults.goalTitle,
        goalDeadline = this[goalDeadlineKey]
            ?.takeUnless { it == DayPreferenceKeys.NO_DEADLINE }
            ?.let(Instant::fromEpochMilliseconds),
        goalStart = this[goalStartKey]
            ?.takeUnless { it == DayPreferenceKeys.NO_DEADLINE }
            ?.let(Instant::fromEpochMilliseconds),
        pomodoroMinutes = this[pomodoroMinutesKey] ?: defaults.pomodoroMinutes,
        pomodoroEnd = this[pomodoroEndKey]
            ?.takeUnless { it == DayPreferenceKeys.NO_DEADLINE }
            ?.let(Instant::fromEpochMilliseconds),
        openDetourStart = this[openDetourStartKey]
            ?.takeUnless { it == DayPreferenceKeys.NO_DEADLINE }
            ?.let(Instant::fromEpochMilliseconds),
        openDetourCategory = this[openDetourCategoryKey] ?: defaults.openDetourCategory,
        openDetourDescription = this[openDetourDescriptionKey] ?: defaults.openDetourDescription,
        focusIntention = this[focusIntentionKey] ?: defaults.focusIntention,
        netTimeSettings = NetTimeSettings(
            enabled = this[netTimeEnabledKey] ?: false,
            includedCalendarIds = this[netTimeCalendarsKey].orEmpty()
                .split("\n").filter { it.isNotBlank() }.toSet(),
        ),
        busyDayKey = this[busyDayPrefKey] ?: defaults.busyDayKey,
        busyIntervals = decodeBusyIntervals(this[busyIntervalsKey].orEmpty()),
        availableCalendars = decodeCalendarNames(this[availableCalendarsKey].orEmpty())
            .map { CalendarInfo(it.key, it.value) },
        onGoalApps = decodeAppRefs(this[onGoalAppsKey].orEmpty()),
        detoursDayKey = this[detoursDayPrefKey] ?: defaults.detoursDayKey,
        detours = decodeDetours(this[detoursKey].orEmpty()),
        recentDetourCategories = decodeRecentDetourCategories(this[detourRecentCategoriesKey].orEmpty()),
        plannedObligationsDayKey = this[plannedObligationsDayPrefKey] ?: defaults.plannedObligationsDayKey,
        plannedObligations = decodePlannedObligations(this[plannedObligationsKey].orEmpty()),
        plannedObligationsCompleted = decodePlannedObligations(this[plannedObligationsCompletedKey].orEmpty()),
        themeMode = this[themeModeKey]
            ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
            ?: defaults.themeMode,
        cleanSessions = CleanSessionLedger(
            dayKey = this[cleanSessionsDayKey] ?: defaults.cleanSessions.dayKey,
            cleanToday = this[cleanSessionsTodayKey] ?: defaults.cleanSessions.cleanToday,
            streakDays = this[cleanStreakDaysKey] ?: defaults.cleanSessions.streakDays,
            streakLastDayKey = this[cleanStreakLastDayKey] ?: defaults.cleanSessions.streakLastDayKey,
        ),
        fontScale = (this[fontScaleKey] ?: defaults.fontScale).coerceIn(1.0f, 1.5f),
    )
}
