package fr.dayview.app

import android.content.Context
import android.content.SharedPreferences

class AndroidDayPreferences(
    context: Context,
    private val notifyWidgets: Boolean = true,
) : DayPreferences {
    private val appContext = context.applicationContext
    private val storage = context.getSharedPreferences("dayview_preferences", Context.MODE_PRIVATE)

    private fun refreshWidgets() {
        if (notifyWidgets) DayViewWidget.updateAll(appContext)
    }

    override fun observe(observer: (DayPreferencesSnapshot) -> Unit): () -> Unit {
        var last = snapshot()
        observer(last)
        // SharedPreferences notifies once per changed key; dedup so a multi-key
        // write (e.g. saveDayRange) yields a single snapshot per logical change.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            val current = snapshot()
            if (current != last) {
                last = current
                observer(current)
            }
        }
        storage.registerOnSharedPreferenceChangeListener(listener)
        return { storage.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    override fun loadStartMinutes(): Int = storage.getInt(KEY_START, DEFAULT_START)

    override fun loadEndMinutes(): Int = storage.getInt(KEY_END, DEFAULT_END)

    override fun saveDayRange(startMinutes: Int, endMinutes: Int) {
        storage.edit()
            .putInt(KEY_START, startMinutes)
            .putInt(KEY_END, endMinutes)
            .apply()
        refreshWidgets()
    }

    override fun loadShowSeconds(): Boolean = storage.getBoolean(KEY_SHOW_SECONDS, true)

    override fun saveShowSeconds(showSeconds: Boolean) {
        storage.edit().putBoolean(KEY_SHOW_SECONDS, showSeconds).apply()
    }

    override fun loadSoundSettings(): SoundSettings = SoundSettings(
        enabled = storage.getBoolean(KEY_SOUND_ENABLED, false),
        startCueEnabled = storage.getBoolean(KEY_SOUND_START, true),
        intervalCueEnabled = storage.getBoolean(KEY_SOUND_INTERVAL, true),
        endCueEnabled = storage.getBoolean(KEY_SOUND_END, true),
        intervalMinutes = storage.getInt(KEY_SOUND_INTERVAL_MINUTES, 30),
        volumePercent = storage.getInt(KEY_SOUND_VOLUME, 40),
    ).normalized()

    override fun saveSoundSettings(settings: SoundSettings) {
        val safe = settings.normalized()
        storage.edit()
            .putBoolean(KEY_SOUND_ENABLED, safe.enabled)
            .putBoolean(KEY_SOUND_START, safe.startCueEnabled)
            .putBoolean(KEY_SOUND_INTERVAL, safe.intervalCueEnabled)
            .putBoolean(KEY_SOUND_END, safe.endCueEnabled)
            .putInt(KEY_SOUND_INTERVAL_MINUTES, safe.intervalMinutes)
            .putInt(KEY_SOUND_VOLUME, safe.volumePercent)
            .apply()
    }

    override fun loadGoalTitle(): String = storage.getString(KEY_GOAL_TITLE, "").orEmpty()

    override fun loadGoalDeadlineMillis(): Long? = storage.getLong(KEY_GOAL_DEADLINE, NO_DEADLINE).takeUnless { it == NO_DEADLINE }

    override fun loadGoalStartMillis(): Long? = storage.getLong(KEY_GOAL_START, NO_DEADLINE).takeUnless { it == NO_DEADLINE }

    override fun saveGlobalGoal(title: String, deadlineMillis: Long?, startMillis: Long?) {
        storage.edit()
            .putString(KEY_GOAL_TITLE, title)
            .putLong(KEY_GOAL_DEADLINE, deadlineMillis ?: NO_DEADLINE)
            .putLong(KEY_GOAL_START, startMillis ?: NO_DEADLINE)
            .apply()
        refreshWidgets()
    }

    override fun loadPomodoroMinutes(): Int = storage.getInt(KEY_POMODORO_MINUTES, 25)

    override fun loadPomodoroEndMillis(): Long? = storage.getLong(KEY_POMODORO_END, NO_DEADLINE).takeUnless { it == NO_DEADLINE }

    override fun savePomodoro(durationMinutes: Int, endMillis: Long?) {
        storage.edit()
            .putInt(KEY_POMODORO_MINUTES, durationMinutes)
            .putLong(KEY_POMODORO_END, endMillis ?: NO_DEADLINE)
            .apply()
        refreshWidgets()
    }

    override fun loadFocusIntention(): String = storage.getString(KEY_FOCUS_INTENTION, "").orEmpty()

    override fun saveFocusIntention(intention: String) {
        storage.edit().putString(KEY_FOCUS_INTENTION, intention).apply()
        refreshWidgets()
    }

    override fun loadNetTimeSettings(): NetTimeSettings = NetTimeSettings(
        enabled = storage.getBoolean(KEY_NET_TIME_ENABLED, false),
        includedCalendarIds = storage.getString(KEY_NET_TIME_CALENDARS, "").orEmpty()
            .split("\n").filter { it.isNotBlank() }.toSet(),
    )

    override fun saveNetTimeSettings(settings: NetTimeSettings) {
        storage.edit()
            .putBoolean(KEY_NET_TIME_ENABLED, settings.enabled)
            .putString(KEY_NET_TIME_CALENDARS, settings.includedCalendarIds.joinToString("\n"))
            .apply()
    }

    override fun loadOnGoalApps(goalId: String): Set<AppRef> = decodeAppRefs(storage.getString(KEY_ON_GOAL_APPS_PREFIX + goalId, "").orEmpty())

    override fun saveOnGoalApps(goalId: String, apps: Set<AppRef>) {
        storage.edit().putString(KEY_ON_GOAL_APPS_PREFIX + goalId, encodeAppRefs(apps)).apply()
    }

    override fun loadFocusPresence(): Pair<Long, List<FocusPresenceInterval>> {
        val day = storage.getLong(KEY_FOCUS_PRESENCE_DAY, -1L)
        val intervals = storage.getString(KEY_FOCUS_PRESENCE, "").orEmpty()
            .split("\n")
            .mapNotNull { line ->
                val parts = line.split(",")
                val s = parts.getOrNull(0)?.toLongOrNull()
                val e = parts.getOrNull(1)?.toLongOrNull()
                if (parts.size == 2 && s != null && e != null) FocusPresenceInterval(s, e) else null
            }
        return day to intervals
    }

    override fun saveFocusPresence(dayKey: Long, intervals: List<FocusPresenceInterval>) {
        storage.edit()
            .putLong(KEY_FOCUS_PRESENCE_DAY, dayKey)
            .putString(KEY_FOCUS_PRESENCE, intervals.joinToString("\n") { "${it.startMillis},${it.endMillis}" })
            .apply()
    }

    private companion object {
        const val KEY_START = "start_minutes"
        const val KEY_END = "end_minutes"
        const val KEY_SHOW_SECONDS = "show_seconds"
        const val KEY_SOUND_ENABLED = "sound_enabled"
        const val KEY_SOUND_START = "sound_start"
        const val KEY_SOUND_INTERVAL = "sound_interval"
        const val KEY_SOUND_END = "sound_end"
        const val KEY_SOUND_INTERVAL_MINUTES = "sound_interval_minutes"
        const val KEY_SOUND_VOLUME = "sound_volume"
        const val KEY_GOAL_TITLE = "goal_title"
        const val KEY_GOAL_DEADLINE = "goal_deadline"
        const val KEY_GOAL_START = "goal_start"
        const val NO_DEADLINE = -1L
        const val KEY_POMODORO_MINUTES = "pomodoro_minutes"
        const val KEY_POMODORO_END = "pomodoro_end"
        const val KEY_FOCUS_INTENTION = "focus_intention"
        const val KEY_NET_TIME_ENABLED = "net_time_enabled"
        const val KEY_NET_TIME_CALENDARS = "net_time_calendars"
        const val KEY_ON_GOAL_APPS_PREFIX = "on_goal_apps."
        const val KEY_FOCUS_PRESENCE_DAY = "focus_presence_day"
        const val KEY_FOCUS_PRESENCE = "focus_presence"
        const val DEFAULT_START = 8 * 60
        const val DEFAULT_END = 18 * 60
    }
}
