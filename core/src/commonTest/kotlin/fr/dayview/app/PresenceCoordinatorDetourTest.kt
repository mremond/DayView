package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Ticks are one second apart on purpose. `FocusResumeDetector` treats any gap of 15 s or more
 * as a resumption, and a resumption suppresses the drift nudge for that tick — minute-spaced
 * ticks would make the nudge assertions pass for entirely the wrong reason.
 */
class PresenceCoordinatorDetourTest {
    private val start = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val onGoalApp = "com.example.editor"
    private val offGoalApp = "com.example.social"
    private val onGoal = setOf(onGoalApp)

    private fun PresenceCoordinator.tickAt(
        second: Int,
        detourOpen: Boolean,
        bundleId: String = onGoalApp,
    ): PresenceCoordinator.Result = observe(
        now = start + second.seconds,
        isFocusActive = true,
        frontmostBundleId = bundleId,
        onGoalBundleIds = onGoal,
        pomodoroEnd = start + 60.minutes,
        dayKey = dayKeyOf(start),
        detourOpen = detourOpen,
    )

    private fun PresenceCoordinator.Result.engagedMinutes(): Long = sessionIntervals.sumOf { (it.end - it.start).inWholeMinutes }

    @Test
    fun anOpenDetourStopsEngagedTimeEvenOnAnOnGoalApp() {
        val coordinator = PresenceCoordinator()
        // Five minutes of genuine engagement on an on-goal app.
        for (second in 0..300) coordinator.tickAt(second, detourOpen = false)
        // Detour declared, frontmost app unchanged: rabbit-holing inside the editor.
        var settled = 0L
        for (second in 301..400) settled = coordinator.tickAt(second, detourOpen = true).engagedMinutes()
        var later = 0L
        for (second in 401..900) later = coordinator.tickAt(second, detourOpen = true).engagedMinutes()

        assertTrue(settled > 0, "engaged time must have accumulated before the detour")
        assertEquals(settled, later, "a declared detour must outrank the on-goal app classification")
    }

    @Test
    fun theDriftNudgeFiresOnASustainedOffGoalApp() {
        // Control: without this, the suppression test below cannot tell "suppressed" from
        // "would never have fired anyway".
        val coordinator = PresenceCoordinator()
        val fired = (0..300).any {
            coordinator.tickAt(it, detourOpen = false, bundleId = offGoalApp).driftReminderAt != null
        }
        assertTrue(fired, "sustained off-goal must nudge when no detour is declared")
    }

    @Test
    fun anOpenDetourSuppressesTheDriftNudge() {
        val coordinator = PresenceCoordinator()
        val fired = (0..300).any {
            coordinator.tickAt(it, detourOpen = true, bundleId = offGoalApp).driftReminderAt != null
        }
        assertFalse(fired, "no nudge while the detour is declared — you already know")
    }

    @Test
    fun noImmediateNudgeOnFirstTickAfterADetourEnds() {
        val coordinator = PresenceCoordinator()
        // Off-goal for under a minute before the detour opens: past the 30s initial grace so
        // offGoalSince is set, but well short of the 120s sustained threshold, so nothing has
        // fired yet and the reminder cooldown is still unset.
        for (second in 0..40) coordinator.tickAt(second, detourOpen = false, bundleId = offGoalApp)
        // Detour open well past the sustained threshold. If the drift detector kept observing
        // underneath (the fix), it naturally "fires" once mid-detour (discarded — no visible
        // nudge) and starts a fresh 5-minute cooldown. If it went stale (the bug), none of that
        // happens and its pre-detour offGoalSince timestamp just sits there.
        for (second in 41..300) coordinator.tickAt(second, detourOpen = true, bundleId = offGoalApp)
        // Detour closes; frontmost app is still off-goal. The very first tick must not nudge —
        // a stale offGoalSince would make `now - offGoalSince` look sustained immediately, with
        // no grace period.
        val result = coordinator.tickAt(301, detourOpen = false, bundleId = offGoalApp)
        assertEquals(
            null,
            result.driftReminderAt,
            "no immediate nudge on the first tick after a detour ends",
        )
    }
}
