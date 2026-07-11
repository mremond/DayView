package fr.dayview.app

import java.util.prefs.Preferences

class DesktopDayPreferences internal constructor(
    private val storage: Preferences = Preferences.userNodeForPackage(DesktopDayPreferences::class.java),
) : DayPreferences {
    private val observers = mutableMapOf<Long, (DayPreferencesSnapshot) -> Unit>()
    private var nextObserverId = 0L

    private fun preferencesChanged() {
        val updated = snapshot()
        observers.values.toList().forEach { it(updated) }
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
        storage.putInt(KEY_START, startMinutes)
        storage.putInt(KEY_END, endMinutes)
        preferencesChanged()
    }

    override fun loadShowSeconds(): Boolean = storage.getBoolean(KEY_SHOW_SECONDS, true)

    override fun saveShowSeconds(showSeconds: Boolean) {
        storage.putBoolean(KEY_SHOW_SECONDS, showSeconds)
        preferencesChanged()
    }

    fun loadMonochromeMenuBarIcon(): Boolean = storage.getBoolean(KEY_MONOCHROME_MENU_BAR_ICON, false)

    fun saveMonochromeMenuBarIcon(monochrome: Boolean) {
        storage.putBoolean(KEY_MONOCHROME_MENU_BAR_ICON, monochrome)
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
        storage.putBoolean(KEY_SOUND_ENABLED, safe.enabled)
        storage.putBoolean(KEY_SOUND_START, safe.startCueEnabled)
        storage.putBoolean(KEY_SOUND_INTERVAL, safe.intervalCueEnabled)
        storage.putBoolean(KEY_SOUND_END, safe.endCueEnabled)
        storage.putInt(KEY_SOUND_INTERVAL_MINUTES, safe.intervalMinutes)
        storage.putInt(KEY_SOUND_VOLUME, safe.volumePercent)
        preferencesChanged()
    }

    override fun loadGoalTitle(): String = storage.get(KEY_GOAL_TITLE, "")

    override fun loadGoalDeadlineMillis(): Long? = storage.getLong(KEY_GOAL_DEADLINE, NO_DEADLINE).takeUnless { it == NO_DEADLINE }

    override fun loadGoalStartMillis(): Long? = storage.getLong(KEY_GOAL_START, NO_DEADLINE).takeUnless { it == NO_DEADLINE }

    override fun saveGlobalGoal(title: String, deadlineMillis: Long?, startMillis: Long?) {
        storage.put(KEY_GOAL_TITLE, title)
        storage.putLong(KEY_GOAL_DEADLINE, deadlineMillis ?: NO_DEADLINE)
        storage.putLong(KEY_GOAL_START, startMillis ?: NO_DEADLINE)
        preferencesChanged()
    }

    override fun loadPomodoroMinutes(): Int = storage.getInt(KEY_POMODORO_MINUTES, 25)

    override fun loadPomodoroEndMillis(): Long? = storage.getLong(KEY_POMODORO_END, NO_DEADLINE).takeUnless { it == NO_DEADLINE }

    override fun savePomodoro(durationMinutes: Int, endMillis: Long?) {
        storage.putInt(KEY_POMODORO_MINUTES, durationMinutes)
        storage.putLong(KEY_POMODORO_END, endMillis ?: NO_DEADLINE)
        preferencesChanged()
    }

    override fun loadFocusIntention(): String = storage.get(KEY_FOCUS_INTENTION, "")

    override fun saveFocusIntention(intention: String) {
        storage.put(KEY_FOCUS_INTENTION, intention)
        preferencesChanged()
    }

    override fun loadNetTimeSettings(): NetTimeSettings = NetTimeSettings(
        enabled = storage.getBoolean(KEY_NET_TIME_ENABLED, false),
        includedCalendarIds = storage.get(KEY_NET_TIME_CALENDARS, "")
            .split("\n").filter { it.isNotBlank() }.toSet(),
    )

    override fun saveNetTimeSettings(settings: NetTimeSettings) {
        storage.putBoolean(KEY_NET_TIME_ENABLED, settings.enabled)
        storage.put(KEY_NET_TIME_CALENDARS, settings.includedCalendarIds.joinToString("\n"))
    }

    override fun loadOnGoalApps(goalId: String): Set<AppRef> = decodeAppRefs(storage.get(KEY_ON_GOAL_APPS_PREFIX + goalId, ""))

    override fun saveOnGoalApps(goalId: String, apps: Set<AppRef>) {
        storage.put(KEY_ON_GOAL_APPS_PREFIX + goalId, encodeAppRefs(apps))
        preferencesChanged()
    }

    override fun loadFocusPresence(): Pair<Long, List<FocusPresenceInterval>> {
        val day = storage.getLong(KEY_FOCUS_PRESENCE_DAY, -1L)
        val intervals = storage.get(KEY_FOCUS_PRESENCE, "")
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
        storage.putLong(KEY_FOCUS_PRESENCE_DAY, dayKey)
        storage.put(KEY_FOCUS_PRESENCE, intervals.joinToString("\n") { "${it.startMillis},${it.endMillis}" })
        preferencesChanged()
    }

    private companion object {
        const val KEY_START = "start_minutes"
        const val KEY_END = "end_minutes"
        const val KEY_SHOW_SECONDS = "show_seconds"
        const val KEY_MONOCHROME_MENU_BAR_ICON = "monochrome_menu_bar_icon"
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
