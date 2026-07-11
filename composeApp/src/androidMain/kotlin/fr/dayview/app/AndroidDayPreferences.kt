package fr.dayview.app

import android.content.Context

class AndroidDayPreferences(context: Context) : DayPreferences {
    private val storage = context.getSharedPreferences("dayview_preferences", Context.MODE_PRIVATE)

    override fun loadStartMinutes(): Int = storage.getInt(KEY_START, DEFAULT_START)

    override fun loadEndMinutes(): Int = storage.getInt(KEY_END, DEFAULT_END)

    override fun saveDayRange(startMinutes: Int, endMinutes: Int) {
        storage.edit()
            .putInt(KEY_START, startMinutes)
            .putInt(KEY_END, endMinutes)
            .apply()
    }

    override fun loadGoalTitle(): String = storage.getString(KEY_GOAL_TITLE, "").orEmpty()

    override fun loadGoalDeadlineMillis(): Long? =
        storage.getLong(KEY_GOAL_DEADLINE, NO_DEADLINE).takeUnless { it == NO_DEADLINE }

    override fun saveGlobalGoal(title: String, deadlineMillis: Long?) {
        storage.edit()
            .putString(KEY_GOAL_TITLE, title)
            .putLong(KEY_GOAL_DEADLINE, deadlineMillis ?: NO_DEADLINE)
            .apply()
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
