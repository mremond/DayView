package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class PresenceCoordinatorTest {
    private val onGoal = setOf("com.on.goal")

    private fun tickSeries(
        coordinator: PresenceCoordinator,
        startMillis: Long,
        seconds: Int,
        bundleId: String?,
        pomodoroEnd: Instant,
    ): PresenceCoordinator.Result {
        var result = PresenceCoordinator.Result(emptyList(), emptyList(), kotlin.time.Duration.ZERO)
        for (i in 0..seconds) {
            val now = Instant.fromEpochMilliseconds(startMillis + i * 1000L)
            result = coordinator.observe(
                now = now,
                isFocusActive = true,
                frontmostBundleId = bundleId,
                onGoalBundleIds = onGoal,
                pomodoroEnd = pomodoroEnd,
                dayKey = dayKeyOf(now),
            )
        }
        return result
    }

    @Test
    fun onGoalPresenceAccumulatesEngagedTime() {
        val coordinator = PresenceCoordinator()
        val start = 1_699_956_000_000L
        val end = Instant.fromEpochMilliseconds(start + 25 * 60_000L)
        // 3 minutes on-goal (session minInterval is 60s, so this commits on session end).
        val result = tickSeries(coordinator, start, 180, "com.on.goal", end)
        // Both accumulators have an open run; ending the session commits both.
        assertTrue(result.sessionIntervals.isNotEmpty())
        assertTrue(result.presenceIntervals.isNotEmpty())
        assertEquals(kotlin.time.Duration.ZERO, result.sessionOffGoal) // never off-goal
    }

    @Test
    fun offGoalAccruesSessionOffGoal() {
        val coordinator = PresenceCoordinator()
        val start = 1_699_956_000_000L
        val end = Instant.fromEpochMilliseconds(start + 25 * 60_000L)
        val result = tickSeries(coordinator, start, 120, "com.other.app", end)
        assertTrue(result.sessionOffGoal > kotlin.time.Duration.ZERO)
    }

    @Test
    fun idleWhenNoFocus() {
        val coordinator = PresenceCoordinator()
        val now = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        val result = coordinator.observe(
            now = now,
            isFocusActive = false,
            frontmostBundleId = "com.on.goal",
            onGoalBundleIds = onGoal,
            pomodoroEnd = null,
            dayKey = dayKeyOf(now),
        )
        assertTrue(result.presenceIntervals.isEmpty())
        assertTrue(result.sessionIntervals.isEmpty())
    }

    @Test
    fun rapidAppSwitchingRaisesADriftReminder() {
        val coordinator = PresenceCoordinator()
        val start = 1_699_956_000_000L
        val end = Instant.fromEpochMilliseconds(start + 25 * 60_000L)
        // Past the 30s initial grace, then four distinct frontmost apps inside 45s.
        var fired: Instant? = null
        val apps = listOf("com.a", "com.b", "com.c", "com.d", "com.e")
        for (i in 0..70) {
            val now = Instant.fromEpochMilliseconds(start + i * 1000L)
            val bundle = if (i < 40) "com.other" else apps[(i - 40) / 2 % apps.size]
            val r = coordinator.observe(
                now = now,
                isFocusActive = true,
                frontmostBundleId = bundle,
                onGoalBundleIds = setOf("com.on.goal"),
                pomodoroEnd = end,
                dayKey = dayKeyOf(now),
            )
            if (r.driftReminderAt != null) fired = r.driftReminderAt
        }
        assertTrue(fired != null, "rapid switching should raise a drift reminder")
    }

    @Test
    fun sustainedOffGoalRaisesADriftReminder() {
        val coordinator = PresenceCoordinator()
        val start = 1_699_956_000_000L
        val end = Instant.fromEpochMilliseconds(start + 25 * 60_000L)
        var fired: Instant? = null
        // One off-goal app held well past the 2-minute sustained rule.
        for (i in 0..200) {
            val now = Instant.fromEpochMilliseconds(start + i * 1000L)
            val r = coordinator.observe(
                now = now,
                isFocusActive = true,
                frontmostBundleId = "com.other",
                onGoalBundleIds = setOf("com.on.goal"),
                pomodoroEnd = end,
                dayKey = dayKeyOf(now),
            )
            if (r.driftReminderAt != null) fired = r.driftReminderAt
        }
        assertTrue(fired != null, "a sustained off-goal stretch should raise a drift reminder")
    }

    @Test
    fun noDriftReminderWhileStayingOnGoal() {
        val coordinator = PresenceCoordinator()
        val start = 1_699_956_000_000L
        val end = Instant.fromEpochMilliseconds(start + 25 * 60_000L)
        for (i in 0..200) {
            val now = Instant.fromEpochMilliseconds(start + i * 1000L)
            val r = coordinator.observe(
                now = now,
                isFocusActive = true,
                frontmostBundleId = "com.on.goal",
                onGoalBundleIds = setOf("com.on.goal"),
                pomodoroEnd = end,
                dayKey = dayKeyOf(now),
            )
            assertEquals(null, r.driftReminderAt)
        }
    }

    @Test
    fun aStillActiveSessionOnFirstObservationRaisesTheResumeRitual() {
        val coordinator = PresenceCoordinator()
        val now = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        val r = coordinator.observe(
            now = now,
            isFocusActive = true,
            frontmostBundleId = "com.on.goal",
            onGoalBundleIds = setOf("com.on.goal"),
            pomodoroEnd = Instant.fromEpochMilliseconds(1_699_956_000_000L + 25 * 60_000L),
            dayKey = dayKeyOf(now),
        )
        assertEquals(now, r.resumeRitualAt)
        // The resume tick suppresses a drift nudge (JVM else-if ordering).
        assertEquals(null, r.driftReminderAt)
    }

    @Test
    fun noResumeRitualWhenIdle() {
        val coordinator = PresenceCoordinator()
        val now = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        val r = coordinator.observe(
            now = now,
            isFocusActive = false,
            frontmostBundleId = null,
            onGoalBundleIds = emptySet(),
            pomodoroEnd = null,
            dayKey = dayKeyOf(now),
        )
        assertEquals(null, r.resumeRitualAt)
        assertEquals(null, r.driftReminderAt)
    }
}
