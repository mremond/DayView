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

    override fun loadGoalTitle(): String = storage.get(KEY_GOAL_TITLE, "")

    override fun loadGoalDeadlineMillis(): Long? =
        storage.getLong(KEY_GOAL_DEADLINE, NO_DEADLINE).takeUnless { it == NO_DEADLINE }

    override fun saveGlobalGoal(title: String, deadlineMillis: Long?) {
        storage.put(KEY_GOAL_TITLE, title)
        storage.putLong(KEY_GOAL_DEADLINE, deadlineMillis ?: NO_DEADLINE)
    }

    private companion object {
        const val KEY_START = "start_minutes"
        const val KEY_END = "end_minutes"
        const val KEY_GOAL_TITLE = "goal_title"
        const val KEY_GOAL_DEADLINE = "goal_deadline"
        const val NO_DEADLINE = -1L
        const val DEFAULT_START = 8 * 60
        const val DEFAULT_END = 18 * 60
    }
}
