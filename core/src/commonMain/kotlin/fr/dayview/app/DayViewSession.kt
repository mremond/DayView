package fr.dayview.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
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
    private val frontmostAppProvider: FrontmostAppProvider = NoopFrontmostAppProvider,
    private val now: () -> Instant = { Clock.System.now() },
    private val dayViewBundleId: String = DAYVIEW_BUNDLE_ID,
    private val dockAttention: DockAttentionProvider = NoopDockAttentionProvider,
    private val presencePersistence: PresencePersistence = NoopPresencePersistence,
) {
    private var ticksSinceCalendarRefresh = 0
    private val presence = PresenceCoordinator(dayViewBundleId)
    private var driftReminderId: Instant? = null
    private var resumeRitualId: Instant? = null
    private var lastBouncedFor: Instant? = null
    private var lastAppliedBadge: Boolean = false

    // What the store already holds, and when it was last written. Comparing against the
    // persisted lists (rather than the previous tick) means a skipped write is retried on
    // the next tick instead of being lost.
    private var savedPresence: List<FocusPresenceInterval> = emptyList()
    private var savedSession: List<FocusPresenceInterval> = emptyList()
    private var lastPresenceSave: Instant = Instant.DISTANT_PAST

    // The latch fields above live outside controller.stateFlow, so a dismiss that doesn't
    // otherwise change controller state (tick() with an unchanged `now`) would be silently
    // swallowed by StateFlow's equality-based conflation. This counter is combined into the
    // subscription below purely to force a fresh emission whenever a latch changes.
    private val latchVersion = MutableStateFlow(0)

    private fun bumpLatchVersion() {
        latchVersion.value++
    }

    init {
        refreshCalendar()
        run {
            val state = controller.stateFlow.value
            presence.restore(state.focusPresenceIntervals, state.focusSessionIntervals, dayKeyOf(state.now))
            savedPresence = state.focusPresenceIntervals
            savedSession = state.focusSessionIntervals
        }
    }
    fun currentSnapshot(): TodaySnapshot = controller.stateFlow.value.toTodaySnapshot(use24Hour, driftReminderId != null, resumeRitualId != null)

    fun subscribe(onEach: (TodaySnapshot) -> Unit): DayViewSubscription {
        val job = scope.launch {
            combine(controller.stateFlow, latchVersion) { state, _ -> state }.collect {
                onEach(it.toTodaySnapshot(use24Hour, driftReminderId != null, resumeRitualId != null))
            }
        }
        return object : DayViewSubscription {
            override fun cancel() = job.cancel()
        }
    }

    fun tick() {
        controller.tick(now())
        // JVM cadence: re-probe the calendar once a minute.
        if (++ticksSinceCalendarRefresh >= 60) {
            ticksSinceCalendarRefresh = 0
            refreshCalendar()
        }

        val state = controller.stateFlow.value
        val focusActive = state.focusIsActive
        // JVM parity: the resume detector observes every tick, active or idle, so a cold
        // launch's first manual Start Focus is never mistaken for a recovered session.
        val priorDriftReminderId = driftReminderId
        val priorResumeRitualId = resumeRitualId
        val result = presence.observe(
            now = state.now,
            isFocusActive = focusActive,
            // Match Main.kt: never sample the frontmost app while idle.
            frontmostBundleId = if (focusActive) frontmostAppProvider.frontmostBundleId() else null,
            onGoalBundleIds = state.onGoalApps.map { it.bundleId }.toSet(),
            pomodoroEnd = state.pomodoroEnd,
            dayKey = dayKeyOf(state.now),
            detourOpen = state.openDetourRunning,
        )
        if (result.presenceIntervals != state.focusPresenceIntervals) {
            controller.setFocusPresenceIntervals(result.presenceIntervals)
        }
        if (result.sessionIntervals != state.focusSessionIntervals) {
            controller.setFocusSessionIntervals(result.sessionIntervals)
        }
        controller.setSessionOffGoal(result.sessionOffGoal)
        persistPresenceIfDue(state.now, dayKeyOf(state.now), result.presenceIntervals, result.sessionIntervals)

        // Latch the attention signals (a reminder persists until dismissed).
        result.resumeRitualAt?.let {
            resumeRitualId = it
            driftReminderId = null // the ritual supersedes a pending nudge (JVM parity)
        }
        result.driftReminderAt?.let { driftReminderId = it }
        if (!focusActive) {
            driftReminderId = null
            resumeRitualId = null
        }
        applyDockAttention()
        if (driftReminderId != priorDriftReminderId || resumeRitualId != priorResumeRitualId) {
            bumpLatchVersion()
        }
    }

    private fun applyDockAttention() {
        val pending = driftReminderId
        val badgeVisible = pending != null
        if (badgeVisible != lastAppliedBadge) {
            lastAppliedBadge = badgeVisible
            dockAttention.setBadge(badgeVisible)
        }
        // One bounce per distinct reminder — the JVM's lastBouncedFor dedupe.
        if (pending != null && pending != lastBouncedFor) {
            lastBouncedFor = pending
            dockAttention.bounceOnce()
        }
        if (pending == null) lastBouncedFor = null
    }

    /**
     * JVM cadence (Main.kt): write on a structural change — a run opened or closed, so a
     * list's size moved — otherwise at most every 30s, since extending an open run only
     * moves its end. The write is launched into the session scope so the 1 Hz tick never
     * blocks on disk.
     */
    private fun persistPresenceIfDue(
        now: Instant,
        dayKey: Long,
        presenceIntervals: List<FocusPresenceInterval>,
        sessionIntervals: List<FocusPresenceInterval>,
    ) {
        if (presenceIntervals == savedPresence && sessionIntervals == savedSession) return
        val structural =
            presenceIntervals.size != savedPresence.size || sessionIntervals.size != savedSession.size
        if (!structural && now - lastPresenceSave < 30.seconds) return
        savedPresence = presenceIntervals
        savedSession = sessionIntervals
        lastPresenceSave = now
        scope.launch {
            // Presence is best-effort telemetry: an unhandled exception here (disk full,
            // read-only volume) would otherwise propagate out of this launched coroutine and,
            // on Kotlin/Native, terminate the whole process mid-Focus. Losing a write is
            // acceptable; crashing is not. Cancellation must still propagate normally.
            try {
                presencePersistence.save(dayKey, presenceIntervals, sessionIntervals)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Swallowed intentionally: see comment above.
            }
        }
    }

    fun dismissDriftReminder() {
        driftReminderId = null
        applyDockAttention()
        bumpLatchVersion()
    }

    fun dismissResumeRitual() {
        resumeRitualId = null
        bumpLatchVersion()
    }

    fun startFocus(intention: String) {
        controller.setFocusIntention(intention)
        controller.startPomodoro()
    }

    /**
     * Ends the session through the closure ritual. [outcome] is one of "COMPLETED",
     * "PROGRESSED", "TO_RESUME" (string-typed for the primitives-only Swift facade,
     * symmetric with TodaySnapshot.pomodoroStatus); anything else degrades to
     * COMPLETED rather than throwing across the FFI boundary.
     *
     * Full arity rather than default parameters: Kotlin defaults change the exported
     * Swift selector, so a later default would silently break the Swift call sites.
     *
     * A [detourCategory] pays the exit toll — leaving before the term with PROGRESSED or
     * TO_RESUME. Without it the controller refuses the closure silently and the session
     * simply keeps running; the Swift sheet keeps Confirm disabled so that never happens.
     */
    fun closeFocus(
        outcome: String,
        intention: String,
        detourCategory: String,
        detourDescription: String,
    ) {
        controller.closePomodoro(
            when (outcome) {
                "PROGRESSED" -> FocusClosureOutcome.PROGRESSED
                "TO_RESUME" -> FocusClosureOutcome.TO_RESUME
                else -> FocusClosureOutcome.COMPLETED
            },
            intention = intention,
            detourCategory = detourCategory,
            detourDescription = detourDescription,
        )
    }

    /**
     * Closes the running open detour into an episode, reusing the motif it was opened
     * with — the same delegation the Compose app does. The controller refuses a blank
     * motif; natively one cannot occur, since only the exit toll opens a detour and it
     * always names it (see the phase 12a spec's note on what changes when sync lands).
     */
    fun stopOpenDetour() {
        controller.stopOpenDetour(controller.stateFlow.value.openDetourCategory)
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

    /** The configured on-goal apps (stored set), for the settings list. */
    fun onGoalApps(): List<AppRef> = controller.stateFlow.value.onGoalApps.toList()

    fun addOnGoalApp(bundleId: String, name: String) {
        val current = controller.stateFlow.value.onGoalApps
        controller.setOnGoalApps(current + AppRef(bundleId, name))
    }

    fun removeOnGoalApp(bundleId: String) {
        val current = controller.stateFlow.value.onGoalApps
        controller.setOnGoalApps(current.filterNot { it.bundleId == bundleId }.toSet())
    }

    fun runningApps(): List<AppRef> = frontmostAppProvider.runningApps().filterNot { it.bundleId == dayViewBundleId }

    fun onGoalBundleIds(): List<String> = controller.stateFlow.value.onGoalApps.map { it.bundleId }

    fun close() = scope.cancel()
}
