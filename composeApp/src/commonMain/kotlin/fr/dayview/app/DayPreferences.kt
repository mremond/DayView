package fr.dayview.app

interface DayPreferences {
    fun loadStartMinutes(): Int
    fun loadEndMinutes(): Int
    fun saveDayRange(startMinutes: Int, endMinutes: Int)
    fun loadShowSeconds(): Boolean
    fun saveShowSeconds(showSeconds: Boolean)
    fun loadSoundSettings(): SoundSettings
    fun saveSoundSettings(settings: SoundSettings)
    fun loadGoalTitle(): String
    fun loadGoalDeadlineMillis(): Long?
    fun saveGlobalGoal(title: String, deadlineMillis: Long?)
    fun loadPomodoroMinutes(): Int
    fun loadPomodoroEndMillis(): Long?
    fun savePomodoro(durationMinutes: Int, endMillis: Long?)
    fun loadFocusIntention(): String
    fun saveFocusIntention(intention: String)
    fun loadNetTimeSettings(): NetTimeSettings
    fun saveNetTimeSettings(settings: NetTimeSettings)
}

object DefaultDayPreferences : DayPreferences {
    override fun loadStartMinutes(): Int = 8 * 60
    override fun loadEndMinutes(): Int = 18 * 60
    override fun saveDayRange(startMinutes: Int, endMinutes: Int) = Unit
    override fun loadShowSeconds(): Boolean = true
    override fun saveShowSeconds(showSeconds: Boolean) = Unit
    override fun loadSoundSettings(): SoundSettings = SoundSettings()
    override fun saveSoundSettings(settings: SoundSettings) = Unit
    override fun loadGoalTitle(): String = ""
    override fun loadGoalDeadlineMillis(): Long? = null
    override fun saveGlobalGoal(title: String, deadlineMillis: Long?) = Unit
    override fun loadPomodoroMinutes(): Int = 25
    override fun loadPomodoroEndMillis(): Long? = null
    override fun savePomodoro(durationMinutes: Int, endMillis: Long?) = Unit
    override fun loadFocusIntention(): String = ""
    override fun saveFocusIntention(intention: String) = Unit
    override fun loadNetTimeSettings(): NetTimeSettings = NetTimeSettings()
    override fun saveNetTimeSettings(settings: NetTimeSettings) = Unit
}
