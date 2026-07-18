package fr.dayview.app

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Drives the two presence accumulators + the cleanliness tracker from a per-tick frontmost
 * sample — the extraction of the JVM Main.kt presence block. Parameters match Main.kt exactly.
 * Stateful (holds open runs); one instance per session lifetime. Never throws.
 */
class PresenceCoordinator(private val dayViewBundleId: String = DAYVIEW_BUNDLE_ID) {
    data class Result(
        val presenceIntervals: List<FocusPresenceInterval>,
        val sessionIntervals: List<FocusPresenceInterval>,
        val sessionOffGoal: Duration,
    )

    private val presenceAccumulator = PresenceAccumulator(bridge = 60.seconds)
    private val sessionAccumulator = PresenceAccumulator(
        presentStates = setOf(OnGoalState.ON_GOAL, OnGoalState.NEUTRAL),
        bridge = 120.seconds,
        minInterval = 60.seconds,
        interruptionGap = 15.seconds,
    )
    private val cleanlinessTracker = SessionCleanlinessTracker()
    private var presence: List<FocusPresenceInterval> = emptyList()
    private var session: List<FocusPresenceInterval> = emptyList()
    private var wasFocusActive = false

    /** Seed the committed intervals from persisted state at session construction. */
    fun restore(presence: List<FocusPresenceInterval>, session: List<FocusPresenceInterval>, dayKey: Long) {
        this.presence = presence
        this.session = session
        presenceAccumulator.restore(presence, dayKey)
        sessionAccumulator.restore(session, dayKey)
    }

    fun observe(
        now: Instant,
        isFocusActive: Boolean,
        frontmostBundleId: String?,
        onGoalBundleIds: Set<String>,
        pomodoroEnd: Instant?,
        dayKey: Long,
    ): Result {
        val state = classifyFrontmost(frontmostBundleId, onGoalBundleIds, dayViewBundleId)
        val offGoal = cleanlinessTracker.observe(now, pomodoroEnd, state)
        presence = when {
            isFocusActive -> presenceAccumulator.observe(now, state, dayKey)
            wasFocusActive -> presenceAccumulator.endSession()
            else -> presence
        }
        session = when {
            isFocusActive -> sessionAccumulator.observe(now, state, dayKey)
            wasFocusActive -> sessionAccumulator.endSession()
            else -> session
        }
        wasFocusActive = isFocusActive
        return Result(presence, session, offGoal)
    }
}
