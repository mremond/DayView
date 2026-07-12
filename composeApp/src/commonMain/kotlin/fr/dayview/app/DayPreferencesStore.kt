package fr.dayview.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
    const val FOCUS_INTENTION = "focus_intention"
    const val NET_TIME_ENABLED = "net_time_enabled"
    const val NET_TIME_CALENDARS = "net_time_calendars"
    const val ON_GOAL_APPS = "on_goal_apps"
    const val DETOURS_DAY = "detours_day"
    const val DETOURS = "detours"
    const val DETOUR_RECENT_MOTIFS = "detour_recent_motifs"
    const val THEME_MODE = "theme_mode"
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
private val focusIntentionKey = stringPreferencesKey(DayPreferenceKeys.FOCUS_INTENTION)
private val netTimeEnabledKey = booleanPreferencesKey(DayPreferenceKeys.NET_TIME_ENABLED)
private val netTimeCalendarsKey = stringPreferencesKey(DayPreferenceKeys.NET_TIME_CALENDARS)
private val onGoalAppsKey = stringPreferencesKey(DayPreferenceKeys.ON_GOAL_APPS)
private val detoursDayPrefKey = longPreferencesKey(DayPreferenceKeys.DETOURS_DAY)
private val detoursKey = stringPreferencesKey(DayPreferenceKeys.DETOURS)
private val detourRecentMotifsKey = stringPreferencesKey(DayPreferenceKeys.DETOUR_RECENT_MOTIFS)
private val themeModeKey = stringPreferencesKey(DayPreferenceKeys.THEME_MODE)

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
            prefs[focusIntentionKey] = snapshot.focusIntention
            prefs[netTimeEnabledKey] = snapshot.netTimeSettings.enabled
            prefs[netTimeCalendarsKey] = snapshot.netTimeSettings.includedCalendarIds.joinToString("\n")
            prefs[onGoalAppsKey] = encodeAppRefs(snapshot.onGoalApps)
            prefs[detoursDayPrefKey] = snapshot.detoursDayKey
            prefs[detoursKey] = encodeDetours(snapshot.detours)
            prefs[detourRecentMotifsKey] = encodeRecentDetourMotifs(snapshot.recentDetourMotifs)
            prefs[themeModeKey] = snapshot.themeMode.name
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
        focusIntention = this[focusIntentionKey] ?: defaults.focusIntention,
        netTimeSettings = NetTimeSettings(
            enabled = this[netTimeEnabledKey] ?: false,
            includedCalendarIds = this[netTimeCalendarsKey].orEmpty()
                .split("\n").filter { it.isNotBlank() }.toSet(),
        ),
        onGoalApps = decodeAppRefs(this[onGoalAppsKey].orEmpty()),
        detoursDayKey = this[detoursDayPrefKey] ?: defaults.detoursDayKey,
        detours = decodeDetours(this[detoursKey].orEmpty()),
        recentDetourMotifs = decodeRecentDetourMotifs(this[detourRecentMotifsKey].orEmpty()),
        themeMode = this[themeModeKey]
            ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
            ?: defaults.themeMode,
    )
}
