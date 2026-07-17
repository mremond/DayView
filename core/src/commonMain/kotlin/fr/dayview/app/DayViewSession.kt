package fr.dayview.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant

/** Handle returned by [DayViewSession.subscribe]; named to avoid Combine's `Cancellable`. */
interface DayViewSubscription {
    fun cancel()
}

/**
 * Native-facing wrapper over [DayViewController]: emits [TodaySnapshot]s and forwards actions.
 * All emissions run on the scope's dispatcher; DayViewNative.create uses the main dispatcher.
 *
 * Intended to be used from the main thread: DayViewNative.create's scope runs on
 * the main dispatcher, and Swift calls tick/actions from the main thread. Controller state
 * mutation is not synchronized, so its thread-safety relies on this invariant.
 */
class DayViewSession internal constructor(
    private val controller: DayViewController,
    private val scope: CoroutineScope,
    private val calendarSource: CalendarSource = NoopCalendarSource,
    private val use24Hour: Boolean = true,
) {
    private var ticksSinceCalendarRefresh = 0

    init {
        refreshCalendar()
    }
    fun currentSnapshot(): TodaySnapshot = controller.stateFlow.value.toTodaySnapshot(use24Hour)

    fun subscribe(onEach: (TodaySnapshot) -> Unit): DayViewSubscription {
        val job = scope.launch {
            controller.stateFlow.collect { onEach(it.toTodaySnapshot(use24Hour)) }
        }
        return object : DayViewSubscription {
            override fun cancel() = job.cancel()
        }
    }

    fun tick() {
        controller.tick(Clock.System.now())
        // JVM cadence: re-probe the calendar once a minute.
        if (++ticksSinceCalendarRefresh >= 60) {
            ticksSinceCalendarRefresh = 0
            refreshCalendar()
        }
    }

    fun startFocus(intention: String) {
        controller.setFocusIntention(intention)
        controller.startPomodoro()
    }

    fun stopFocus() = controller.stopPomodoro()

    /**
     * Ends the session through the closure ritual. [outcome] is one of "COMPLETED",
     * "PROGRESSED", "TO_RESUME" (string-typed for the primitives-only Swift facade,
     * symmetric with TodaySnapshot.pomodoroStatus); anything else degrades to
     * COMPLETED rather than throwing across the FFI boundary.
     */
    fun closeFocus(outcome: String) {
        controller.closePomodoro(
            when (outcome) {
                "PROGRESSED" -> FocusClosureOutcome.PROGRESSED
                "TO_RESUME" -> FocusClosureOutcome.TO_RESUME
                else -> FocusClosureOutcome.COMPLETED
            },
        )
    }

    fun setDayStart(minutes: Int) = controller.setStartMinutes(minutes)

    fun setDayEnd(minutes: Int) = controller.setEndMinutes(minutes)

    fun setShowSeconds(enabled: Boolean) = controller.setShowSeconds(enabled)

    /**
     * [mode] is "SYSTEM"/"LIGHT"/"DARK" (string-typed for the primitives-only Swift
     * facade); anything else degrades to SYSTEM rather than throwing across the FFI
     * boundary.
     */
    fun setThemeMode(mode: String) {
        controller.setThemeMode(
            when (mode) {
                "LIGHT" -> ThemeMode.LIGHT
                "DARK" -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            },
        )
    }

    /** Re-reads the busy layer and injects it. Public: the post-grant callback uses it. */
    fun refreshCalendar() {
        val state = controller.stateFlow.value
        val (start, end) = dayWindow(state.now, state.startMinutes, state.endMinutes)
        val probe = probeNetTime(
            source = calendarSource,
            enabled = state.netTimeSettings.enabled,
            includedCalendarIds = state.netTimeSettings.includedCalendarIds,
            windowStart = start,
            windowEnd = end,
        )
        controller.updateNetTimeData(probe.permission, probe.busy, probe.calendars, probe.readError)
    }

    fun setNetTimeEnabled(enabled: Boolean) {
        val settings = controller.stateFlow.value.netTimeSettings
        controller.setNetTimeSettings(settings.copy(enabled = enabled))
        refreshCalendar()
    }

    fun setCalendarIncluded(id: String, included: Boolean) {
        val state = controller.stateFlow.value
        val settings = state.netTimeSettings
        val allIds = state.availableCalendars.map { it.id }
        controller.setNetTimeSettings(
            settings.copy(
                includedCalendarIds = nextIncludedCalendars(allIds, settings.includedCalendarIds, id, included),
            ),
        )
        refreshCalendar()
    }

    fun requestCalendarAccess() = calendarSource.requestPermission()

    fun changePomodoroDuration(deltaMinutes: Int) = controller.changePomodoroDuration(deltaMinutes)

    fun setGoalTitle(title: String) = controller.setGoalTitle(title)

    fun setGoalDeadline(epochMillis: Long) {
        controller.setGoalDeadlineInstant(
            epochMillis.takeIf { it > 0L }?.let(Instant::fromEpochMilliseconds),
        )
    }

    fun setFocusIntention(intention: String) = controller.setFocusIntention(intention)

    fun addDetour(category: String, durationMinutes: Int, description: String) = controller.addDetour(category, durationMinutes, description)

    fun updateDetour(index: Int, startEpochMillis: Long, endEpochMillis: Long, category: String, description: String) = controller.updateDetour(
        index,
        DetourEpisode(
            start = Instant.fromEpochMilliseconds(startEpochMillis),
            end = Instant.fromEpochMilliseconds(endEpochMillis),
            category = category,
            description = description,
        ),
    )

    fun removeDetour(index: Int) = controller.removeDetour(index)

    fun restoreLastRemovedDetour() = controller.restoreLastRemovedDetour()

    fun forgetRecentDetourCategory(category: String) = controller.forgetRecentDetourCategory(category)

    fun close() = scope.cancel()
}
