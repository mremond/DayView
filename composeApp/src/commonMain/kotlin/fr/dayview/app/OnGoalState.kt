@file:Suppress("FileName")

package fr.dayview.app

enum class OnGoalState { ON_GOAL, OFF_GOAL, NEUTRAL }

/** Classify the frontmost app for a focus session. DayView itself / blank is neutral. */
fun classifyFrontmost(
    frontmostBundleId: String?,
    onGoalBundleIds: Set<String>,
    dayViewBundleId: String = "fr.dayview.app",
): OnGoalState = when {
    frontmostBundleId.isNullOrBlank() -> OnGoalState.NEUTRAL
    frontmostBundleId == dayViewBundleId -> OnGoalState.NEUTRAL
    frontmostBundleId in onGoalBundleIds -> OnGoalState.ON_GOAL
    else -> OnGoalState.OFF_GOAL
}
