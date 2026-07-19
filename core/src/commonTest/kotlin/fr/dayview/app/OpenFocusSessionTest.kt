package fr.dayview.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The term is an invitation, not an ending: a session that has run past it is still open.
 * Everything keyed on "a session is running" — engaged and presence time, the day's cues,
 * the resumption ritual, drift detection — has to follow it there.
 */
class OpenFocusSessionTest {
    private val start = Instant.fromEpochMilliseconds(1_699_956_000_000L)
    private val end = start + 25.minutes
    private val onGoal = setOf("com.on.goal")
    private val running = DayPreferencesSnapshot(pomodoroEnd = end, pomodoroSessionMinutes = 25)

    private fun stateAt(now: Instant, pomodoroEnd: Instant?) = DayViewUiState(
        now = now,
        startMinutes = 8 * 60,
        endMinutes = 18 * 60,
        showSeconds = false,
        soundSettings = SoundSettings(enabled = true),
        goalTitle = "",
        goalDeadlineText = "",
        goalDeadline = null,
        goalStartText = "",
        goalStart = null,
        pomodoroMinutes = 25,
        pomodoroEnd = pomodoroEnd,
        focusIntention = "",
        pomodoroSessionMinutes = pomodoroEnd?.let { 25 },
    )

    @Test
    fun overtimeIsStillAnOpenSession() {
        val overtime = stateAt(end + 12.minutes, end)
        assertEquals(PomodoroStatus.OVERTIME, overtime.pomodoroProgress.status)
        assertTrue(overtime.focusIsActive)
        // Closed rather than overrun: nothing is running any more.
        assertFalse(stateAt(end + 12.minutes, null).focusIsActive)
    }

    @Test
    fun theSnapshotAndTheControllerStateAgreePastTheTerm() {
        assertTrue(running.focusIsActive)
        assertEquals(stateAt(end + 12.minutes, end).focusIsActive, running.focusIsActive)
        assertEquals(stateAt(end + 12.minutes, null).focusIsActive, DayPreferencesSnapshot().focusIsActive)
    }

    @Test
    fun engagedAndPresenceTimeKeepAccruingPastTheTerm() {
        val coordinator = PresenceCoordinator()
        var result = PresenceCoordinator.Result(emptyList(), emptyList(), Duration.ZERO)
        // Three minutes of on-goal work, every tick of it past the term.
        for (i in 0..180) {
            val now = end + i.seconds
            result = coordinator.observe(
                now = now,
                isFocusActive = running.focusIsActive,
                frontmostBundleId = "com.on.goal",
                onGoalBundleIds = onGoal,
                pomodoroEnd = running.pomodoroEnd,
                dayKey = dayKeyOf(now),
            )
        }
        assertTrue(result.sessionIntervals.isNotEmpty(), "overtime must accrue engaged time")
        assertTrue(result.presenceIntervals.isNotEmpty(), "overtime must accrue presence")
    }

    @Test
    fun theDaysCuesStaySilentPastTheTerm() {
        val settings = SoundSettings(enabled = true)
        // The default 30-minute interval chime lands 5 minutes into the overtime of a
        // default 25-minute session — exactly the hyperfocus that must not be interrupted.
        assertFalse(settings.allowsDayCue(SoundCue.INTERVAL, running.focusIsActive))
        assertTrue(settings.allowsDayCue(SoundCue.INTERVAL, DayPreferencesSnapshot().focusIsActive))
    }

    @Test
    fun relaunchingDuringOvertimeRaisesTheResumptionRitual() {
        val coordinator = PresenceCoordinator()
        val now = end + 12.minutes
        val result = coordinator.observe(
            now = now,
            isFocusActive = running.focusIsActive,
            frontmostBundleId = "com.on.goal",
            onGoalBundleIds = onGoal,
            pomodoroEnd = running.pomodoroEnd,
            dayKey = dayKeyOf(now),
        )
        assertEquals(now, result.resumeRitualAt)
    }

    @Test
    fun driftIsStillWatchedPastTheTerm() {
        val coordinator = PresenceCoordinator()
        var fired: Instant? = null
        // One off-goal app held well past the two-minute sustained rule, all in overtime.
        for (i in 0..200) {
            val now = end + i.seconds
            val r = coordinator.observe(
                now = now,
                isFocusActive = running.focusIsActive,
                frontmostBundleId = "com.other",
                onGoalBundleIds = onGoal,
                pomodoroEnd = running.pomodoroEnd,
                dayKey = dayKeyOf(now),
            )
            if (r.driftReminderAt != null) fired = r.driftReminderAt
        }
        assertTrue(fired != null, "drifting away during overtime should still raise a nudge")
    }

    /**
     * The merge seam between the two branches: a session now stays open past its term, and a
     * detour may be declared while a session runs. Overtime is therefore a window in which an
     * open detour and an open session overlap — reachable on neither branch alone. Everything
     * the open-detour carve-out establishes has to keep holding there.
     */
    @Test
    fun anOpenDetourStillOutranksTheOnGoalAppDuringOvertime() {
        val coordinator = PresenceCoordinator()
        var beforeDetour = Duration.ZERO
        var afterDetour = Duration.ZERO
        // Two minutes on an on-goal app in overtime, then two more with a detour declared:
        // rabbit-holing inside the editor, past the term.
        for (i in 0..120) {
            val now = end + i.seconds
            val r = coordinator.observe(
                now = now,
                isFocusActive = running.focusIsActive,
                frontmostBundleId = "com.on.goal",
                onGoalBundleIds = onGoal,
                pomodoroEnd = running.pomodoroEnd,
                dayKey = dayKeyOf(now),
                detourOpen = false,
            )
            beforeDetour = r.sessionIntervals.fold(Duration.ZERO) { acc, it -> acc + (it.end - it.start) }
        }
        for (i in 121..240) {
            val now = end + i.seconds
            val r = coordinator.observe(
                now = now,
                isFocusActive = running.focusIsActive,
                frontmostBundleId = "com.on.goal",
                onGoalBundleIds = onGoal,
                pomodoroEnd = running.pomodoroEnd,
                dayKey = dayKeyOf(now),
                detourOpen = true,
            )
            afterDetour = r.sessionIntervals.fold(Duration.ZERO) { acc, it -> acc + (it.end - it.start) }
        }
        assertTrue(beforeDetour > Duration.ZERO, "overtime must accrue engaged time before the detour")
        assertEquals(beforeDetour, afterDetour, "a detour declared during overtime must stop engaged time")
    }

    /**
     * [driftIsStillWatchedPastTheTerm] is the control for this one: it proves the same ticks
     * do nudge when no detour is declared, so this assertion cannot pass vacuously.
     */
    @Test
    fun anOpenDetourStillSuppressesTheDriftNudgeDuringOvertime() {
        val coordinator = PresenceCoordinator()
        var fired: Instant? = null
        for (i in 0..200) {
            val now = end + i.seconds
            val r = coordinator.observe(
                now = now,
                isFocusActive = running.focusIsActive,
                frontmostBundleId = "com.other",
                onGoalBundleIds = onGoal,
                pomodoroEnd = running.pomodoroEnd,
                dayKey = dayKeyOf(now),
                detourOpen = true,
            )
            if (r.driftReminderAt != null) fired = r.driftReminderAt
        }
        assertEquals(null, fired, "no nudge in overtime either — the detour is already declared")
    }

    @Test
    fun closingInOvertimeCarvesTheDetourOpenedDuringIt() {
        val controller = DayViewController(
            DefaultDayPreferences,
            CoroutineScope(Dispatchers.Unconfined),
            initialSnapshot = DayPreferencesSnapshot(
                pomodoroEnd = end,
                pomodoroMinutes = 25,
                pomodoroSessionMinutes = 25,
                focusIntention = "écrire",
                // Pulled away five minutes into the overtime, still off-path at closure.
                openDetourStart = end + 5.minutes,
            ),
            initialNow = end + 15.minutes,
            derivesEngagedFromSessions = true,
        )
        // At or past the term, so no detour name is owed whatever the outcome.
        controller.closePomodoro(FocusClosureOutcome.PROGRESSED)
        // The window runs uncapped to the closure (overtime counts) minus the open detour:
        // [start, term+5], not [start, term] and not [start, term+15].
        assertEquals(
            listOf(FocusPresenceInterval(start, end + 5.minutes)),
            controller.state.focusSessionIntervals,
        )
    }
}
