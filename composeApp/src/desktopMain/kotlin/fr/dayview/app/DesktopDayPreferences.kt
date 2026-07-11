package fr.dayview.app

import java.util.prefs.Preferences

class DesktopDayPreferences internal constructor(
    private val storage: Preferences = Preferences.userNodeForPackage(DesktopDayPreferences::class.java),
) : DayPreferences {

    override fun loadStartMinutes(): Int = storage.getInt(KEY_START, DEFAULT_START)

    override fun loadEndMinutes(): Int = storage.getInt(KEY_END, DEFAULT_END)

    override fun saveDayRange(startMinutes: Int, endMinutes: Int) {
        storage.putInt(KEY_START, startMinutes)
        storage.putInt(KEY_END, endMinutes)
    }

    override fun loadShowSeconds(): Boolean = storage.getBoolean(KEY_SHOW_SECONDS, true)

    override fun saveShowSeconds(showSeconds: Boolean) {
        storage.putBoolean(KEY_SHOW_SECONDS, showSeconds)
    }

    override fun loadGoalTitle(): String = storage.get(KEY_GOAL_TITLE, "")

    override fun loadGoalDeadlineMillis(): Long? =
        storage.getLong(KEY_GOAL_DEADLINE, NO_DEADLINE).takeUnless { it == NO_DEADLINE }

    override fun saveGlobalGoal(title: String, deadlineMillis: Long?) {
        storage.put(KEY_GOAL_TITLE, title)
        storage.putLong(KEY_GOAL_DEADLINE, deadlineMillis ?: NO_DEADLINE)
    }

    override fun loadPomodoroMinutes(): Int = storage.getInt(KEY_POMODORO_MINUTES, 25)

    override fun loadPomodoroEndMillis(): Long? =
        storage.getLong(KEY_POMODORO_END, NO_DEADLINE).takeUnless { it == NO_DEADLINE }

    override fun savePomodoro(durationMinutes: Int, endMillis: Long?) {
        storage.putInt(KEY_POMODORO_MINUTES, durationMinutes)
        storage.putLong(KEY_POMODORO_END, endMillis ?: NO_DEADLINE)
    }

    override fun loadFocusIntention(): String = storage.get(KEY_FOCUS_INTENTION, "")

    override fun saveFocusIntention(intention: String) {
        storage.put(KEY_FOCUS_INTENTION, intention)
    }

    private companion object {
        const val KEY_START = "start_minutes"
        const val KEY_END = "end_minutes"
        const val KEY_SHOW_SECONDS = "show_seconds"
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
