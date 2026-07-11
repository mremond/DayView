package fr.dayview.app

interface DayPreferences {
    fun loadStartMinutes(): Int
    fun loadEndMinutes(): Int
    fun saveDayRange(startMinutes: Int, endMinutes: Int)
    fun loadGoalTitle(): String
    fun loadGoalDeadlineMillis(): Long?
    fun saveGlobalGoal(title: String, deadlineMillis: Long?)
    fun loadPomodoroMinutes(): Int
    fun loadPomodoroEndMillis(): Long?
    fun savePomodoro(durationMinutes: Int, endMillis: Long?)
    fun loadFocusIntention(): String
    fun saveFocusIntention(intention: String)
}

object DefaultDayPreferences : DayPreferences {
    override fun loadStartMinutes(): Int = 8 * 60
    override fun loadEndMinutes(): Int = 18 * 60
    override fun saveDayRange(startMinutes: Int, endMinutes: Int) = Unit
    override fun loadGoalTitle(): String = ""
    override fun loadGoalDeadlineMillis(): Long? = null
    override fun saveGlobalGoal(title: String, deadlineMillis: Long?) = Unit
    override fun loadPomodoroMinutes(): Int = 25
    override fun loadPomodoroEndMillis(): Long? = null
    override fun savePomodoro(durationMinutes: Int, endMillis: Long?) = Unit
    override fun loadFocusIntention(): String = ""
    override fun saveFocusIntention(intention: String) = Unit
}
