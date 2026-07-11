package fr.dayview.app

import android.content.Context

class AndroidDayPreferences(
    context: Context,
    private val notifyWidgets: Boolean = true,
) : DayPreferences {
    private val appContext = context.applicationContext
    private val storage = context.getSharedPreferences("dayview_preferences", Context.MODE_PRIVATE)
    private val observers = mutableMapOf<Long, (DayPreferencesSnapshot) -> Unit>()
    private var nextObserverId = 0L

    private fun preferencesChanged(updateWidgets: Boolean = false) {
        val updated = snapshot()
        observers.values.toList().forEach { it(updated) }
        if (updateWidgets && notifyWidgets) DayViewWidget.updateAll(appContext)
    }

    override fun observe(observer: (DayPreferencesSnapshot) -> Unit): () -> Unit {
        val observerId = nextObserverId++
        observers[observerId] = observer
        observer(snapshot())
        return { observers.remove(observerId) }
    }

    override fun loadStartMinutes(): Int = storage.getInt(KEY_START, DEFAULT_START)

    override fun loadEndMinutes(): Int = storage.getInt(KEY_END, DEFAULT_END)

    override fun saveDayRange(startMinutes: Int, endMinutes: Int) {
        storage.edit()
            .putInt(KEY_START, startMinutes)
            .putInt(KEY_END, endMinutes)
            .apply()
        preferencesChanged(updateWidgets = true)
    }

    override fun loadShowSeconds(): Boolean = storage.getBoolean(KEY_SHOW_SECONDS, true)

    override fun saveShowSeconds(showSeconds: Boolean) {
        storage.edit().putBoolean(KEY_SHOW_SECONDS, showSeconds).apply()
        preferencesChanged()
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
        preferencesChanged()
    }

    override fun loadGoalTitle(): String = storage.getString(KEY_GOAL_TITLE, "").orEmpty()

    override fun loadGoalDeadlineMillis(): Long? =
        storage.getLong(KEY_GOAL_DEADLINE, NO_DEADLINE).takeUnless { it == NO_DEADLINE }

    override fun saveGlobalGoal(title: String, deadlineMillis: Long?) {
        storage.edit()
            .putString(KEY_GOAL_TITLE, title)
            .putLong(KEY_GOAL_DEADLINE, deadlineMillis ?: NO_DEADLINE)
            .apply()
        preferencesChanged(updateWidgets = true)
    }

    override fun loadPomodoroMinutes(): Int = storage.getInt(KEY_POMODORO_MINUTES, 25)

    override fun loadPomodoroEndMillis(): Long? =
        storage.getLong(KEY_POMODORO_END, NO_DEADLINE).takeUnless { it == NO_DEADLINE }

    override fun savePomodoro(durationMinutes: Int, endMillis: Long?) {
        storage.edit()
            .putInt(KEY_POMODORO_MINUTES, durationMinutes)
            .putLong(KEY_POMODORO_END, endMillis ?: NO_DEADLINE)
            .apply()
        preferencesChanged(updateWidgets = true)
    }

    override fun loadFocusIntention(): String = storage.getString(KEY_FOCUS_INTENTION, "").orEmpty()

    override fun saveFocusIntention(intention: String) {
        storage.edit().putString(KEY_FOCUS_INTENTION, intention).apply()
        preferencesChanged(updateWidgets = true)
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
        const val NO_DEADLINE = -1L
        const val KEY_POMODORO_MINUTES = "pomodoro_minutes"
        const val KEY_POMODORO_END = "pomodoro_end"
        const val KEY_FOCUS_INTENTION = "focus_intention"
        const val DEFAULT_START = 8 * 60
        const val DEFAULT_END = 18 * 60
    }
}
