package fr.dayview.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    var state: DayViewUiState by mutableStateOf(preferences.snapshot().toUiState(initialNowMillis))
        private set

    fun tick(nowMillis: Long) {
        state = state.copy(nowMillis = nowMillis)
    }

    fun openSettings() {
        state = state.copy(destination = DayViewDestination.SETTINGS)
    }

    fun openToday() {
        state = state.copy(destination = DayViewDestination.TODAY)
    }

    fun setStartMinutes(minutes: Int) {
        val updated = minutes.coerceIn(0, state.endMinutes - 30)
        state = state.copy(startMinutes = updated)
        preferences.saveDayRange(updated, state.endMinutes)
    }

    fun setEndMinutes(minutes: Int) {
        val updated = minutes.coerceIn(state.startMinutes + 30, 23 * 60 + 59)
        state = state.copy(endMinutes = updated)
        preferences.saveDayRange(state.startMinutes, updated)
    }

    fun setShowSeconds(enabled: Boolean) {
        state = state.copy(showSeconds = enabled)
        preferences.saveShowSeconds(enabled)
    }

    fun setSoundSettings(settings: SoundSettings) {
        val normalized = settings.normalized()
        state = state.copy(soundSettings = normalized)
        preferences.saveSoundSettings(normalized)
    }

    fun setGoalTitle(value: String) {
        val updated = value.take(80)
        state = state.copy(goalTitle = updated)
        preferences.saveGlobalGoal(updated, state.goalDeadlineMillis)
    }

    fun setGoalDeadlineText(value: String) {
        state = state.copy(goalDeadlineText = value.take(16))
    }

    fun commitGoalDeadline() {
        val parsed = parseGoalDeadline(state.goalDeadlineText)
        if (parsed == null && state.goalDeadlineText.isNotBlank()) return
        state = state.copy(goalDeadlineMillis = parsed)
        preferences.saveGlobalGoal(state.goalTitle, parsed)
    }

    fun setFocusIntention(value: String) {
        val updated = value.take(100)
        state = state.copy(focusIntention = updated, lastFocusClosure = null)
        preferences.saveFocusIntention(updated)
    }

    fun changePomodoroDuration(deltaMinutes: Int) {
        if (state.pomodoroProgress.status == PomodoroStatus.ACTIVE) return
        val updated = (state.pomodoroMinutes + deltaMinutes).coerceIn(5, 180)
        state = state.copy(pomodoroMinutes = updated, pomodoroEndMillis = null)
        preferences.savePomodoro(updated, null)
    }

    fun startPomodoro() {
        if (state.focusIntention.isBlank()) return
        val endMillis = state.nowMillis + state.pomodoroMinutes * 60_000L
        state = state.copy(pomodoroEndMillis = endMillis, lastFocusClosure = null)
        preferences.savePomodoro(state.pomodoroMinutes, endMillis)
    }

    fun stopPomodoro() {
        state = state.copy(pomodoroEndMillis = null)
        preferences.savePomodoro(state.pomodoroMinutes, null)
    }

    fun closePomodoro(outcome: FocusClosureOutcome) {
        val updatedIntention = focusIntentionAfterClosure(state.focusIntention, outcome)
        val intentionChanged = updatedIntention != state.focusIntention
        state = state.copy(
            pomodoroEndMillis = null,
            focusIntention = updatedIntention,
            lastFocusClosure = outcome,
        )
        preferences.savePomodoro(state.pomodoroMinutes, null)
        if (intentionChanged) preferences.saveFocusIntention(updatedIntention)
    }

    fun onPreferencesChanged(snapshot: DayPreferencesSnapshot) {
        state = state.withPersisted(snapshot)
    }
}

private fun DayPreferencesSnapshot.coerced(): DayPreferencesSnapshot {
    val safeStart = startMinutes.coerceIn(0, 23 * 60 + 29)
    val safeEnd = endMinutes.coerceIn(safeStart + 30, 23 * 60 + 59)
    return copy(
        startMinutes = safeStart,
        endMinutes = safeEnd,
        soundSettings = soundSettings.normalized(),
        pomodoroMinutes = pomodoroMinutes.coerceIn(5, 180),
    )
}

private fun DayPreferencesSnapshot.toUiState(nowMillis: Long): DayViewUiState {
    val safe = coerced()
    return DayViewUiState(
        nowMillis = nowMillis,
        startMinutes = safe.startMinutes,
        endMinutes = safe.endMinutes,
        showSeconds = safe.showSeconds,
        soundSettings = safe.soundSettings,
        goalTitle = safe.goalTitle,
        goalDeadlineText = safe.goalDeadlineMillis?.let(::formatGoalDeadline).orEmpty(),
        goalDeadlineMillis = safe.goalDeadlineMillis,
        pomodoroMinutes = safe.pomodoroMinutes,
        pomodoroEndMillis = safe.pomodoroEndMillis,
        focusIntention = safe.focusIntention,
    )
}

private fun DayViewUiState.withPersisted(snapshot: DayPreferencesSnapshot): DayViewUiState {
    val safe = snapshot.coerced()
    return copy(
        startMinutes = safe.startMinutes,
        endMinutes = safe.endMinutes,
        showSeconds = safe.showSeconds,
        soundSettings = safe.soundSettings,
        goalTitle = safe.goalTitle,
        goalDeadlineMillis = safe.goalDeadlineMillis,
        pomodoroMinutes = safe.pomodoroMinutes,
        pomodoroEndMillis = safe.pomodoroEndMillis,
        focusIntention = safe.focusIntention,
        // Transient fields deliberately preserved: nowMillis, goalDeadlineText,
        // lastFocusClosure, destination.
    )
}
