package fr.dayview.app

import kotlin.time.Clock

internal enum class DayViewDestination {
    TODAY,
    SETTINGS,
}

internal data class DayViewUiState(
    val nowMillis: Long,
    val startMinutes: Int,
    val endMinutes: Int,
    val showSeconds: Boolean,
    val soundSettings: SoundSettings,
    val goalTitle: String,
    val goalDeadlineText: String,
    val goalDeadlineMillis: Long?,
    val pomodoroMinutes: Int,
    val pomodoroEndMillis: Long?,
    val focusIntention: String,
    val lastFocusClosure: FocusClosureOutcome? = null,
    val destination: DayViewDestination = DayViewDestination.TODAY,
) {
    val dayProgress: DayProgress
        get() {
            val dayNowMillis = if (showSeconds) nowMillis else nowMillis - nowMillis % 60_000L
            return calculateDayProgress(dayNowMillis, startMinutes, endMinutes)
        }

    val pomodoroProgress: PomodoroProgress
        get() = calculatePomodoroProgress(nowMillis, pomodoroMinutes, pomodoroEndMillis)

    val focusIsActive: Boolean
        get() = pomodoroEndMillis?.let { it > nowMillis } == true
}

internal class DayViewController(
    private val preferences: DayPreferences,
    initialNowMillis: Long = Clock.System.now().toEpochMilliseconds(),
) {
    var state: DayViewUiState = preferences.snapshot().toUiState(initialNowMillis)
        private set

    fun tick(nowMillis: Long): DayViewUiState = update { copy(nowMillis = nowMillis) }

    fun openSettings(): DayViewUiState = update { copy(destination = DayViewDestination.SETTINGS) }

    fun openToday(): DayViewUiState = update { copy(destination = DayViewDestination.TODAY) }

    fun moveStart(deltaMinutes: Int): DayViewUiState = update {
        val updated = (startMinutes + deltaMinutes).coerceIn(0, endMinutes - 30)
        preferences.saveDayRange(updated, endMinutes)
        copy(startMinutes = updated)
    }

    fun moveEnd(deltaMinutes: Int): DayViewUiState = update {
        val updated = (endMinutes + deltaMinutes).coerceIn(startMinutes + 30, 23 * 60 + 59)
        preferences.saveDayRange(startMinutes, updated)
        copy(endMinutes = updated)
    }

    fun setShowSeconds(enabled: Boolean): DayViewUiState = update {
        preferences.saveShowSeconds(enabled)
        copy(showSeconds = enabled)
    }

    fun setSoundSettings(settings: SoundSettings): DayViewUiState = update {
        val normalized = settings.normalized()
        preferences.saveSoundSettings(normalized)
        copy(soundSettings = normalized)
    }

    fun setGoalTitle(value: String): DayViewUiState = update {
        val updated = value.take(80)
        preferences.saveGlobalGoal(updated, goalDeadlineMillis)
        copy(goalTitle = updated)
    }

    fun setGoalDeadlineText(value: String): DayViewUiState = update {
        val updatedText = value.take(16)
        val parsed = parseGoalDeadline(updatedText)
        if (parsed != null || updatedText.isBlank()) {
            preferences.saveGlobalGoal(goalTitle, parsed)
            copy(goalDeadlineText = updatedText, goalDeadlineMillis = parsed)
        } else {
            copy(goalDeadlineText = updatedText)
        }
    }

    fun setFocusIntention(value: String): DayViewUiState = update {
        val updated = value.take(100)
        preferences.saveFocusIntention(updated)
        copy(focusIntention = updated, lastFocusClosure = null)
    }

    fun changePomodoroDuration(deltaMinutes: Int): DayViewUiState {
        if (state.pomodoroProgress.status == PomodoroStatus.ACTIVE) return state
        return update {
            val updated = (pomodoroMinutes + deltaMinutes).coerceIn(5, 180)
            preferences.savePomodoro(updated, null)
            copy(pomodoroMinutes = updated, pomodoroEndMillis = null)
        }
    }

    fun startPomodoro(): DayViewUiState {
        if (state.focusIntention.isBlank()) return state
        return update {
            val endMillis = nowMillis + pomodoroMinutes * 60_000L
            preferences.savePomodoro(pomodoroMinutes, endMillis)
            copy(pomodoroEndMillis = endMillis, lastFocusClosure = null)
        }
    }

    fun stopPomodoro(): DayViewUiState = update {
        preferences.savePomodoro(pomodoroMinutes, null)
        copy(pomodoroEndMillis = null)
    }

    fun closePomodoro(outcome: FocusClosureOutcome): DayViewUiState = update {
        val updatedIntention = focusIntentionAfterClosure(focusIntention, outcome)
        preferences.savePomodoro(pomodoroMinutes, null)
        if (updatedIntention != focusIntention) preferences.saveFocusIntention(updatedIntention)
        copy(
            pomodoroEndMillis = null,
            focusIntention = updatedIntention,
            lastFocusClosure = outcome,
        )
    }

    private inline fun update(transform: DayViewUiState.() -> DayViewUiState): DayViewUiState {
        state = state.transform()
        return state
    }
}

private fun DayPreferencesSnapshot.toUiState(nowMillis: Long): DayViewUiState {
    val safeStart = startMinutes.coerceIn(0, 23 * 60 + 29)
    val safeEnd = endMinutes.coerceIn(safeStart + 30, 23 * 60 + 59)
    return DayViewUiState(
        nowMillis = nowMillis,
        startMinutes = safeStart,
        endMinutes = safeEnd,
        showSeconds = showSeconds,
        soundSettings = soundSettings.normalized(),
        goalTitle = goalTitle,
        goalDeadlineText = goalDeadlineMillis?.let(::formatGoalDeadline).orEmpty(),
        goalDeadlineMillis = goalDeadlineMillis,
        pomodoroMinutes = pomodoroMinutes.coerceIn(5, 180),
        pomodoroEndMillis = pomodoroEndMillis,
        focusIntention = focusIntention,
    )
}
