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
}
