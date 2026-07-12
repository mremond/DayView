@file:Suppress("FileName")

package fr.dayview.app

/** DayView's own bundle id. It is always neutral and is never offered for on-goal selection. */
const val DAYVIEW_BUNDLE_ID = "fr.dayview.app"

enum class OnGoalState { ON_GOAL, OFF_GOAL, NEUTRAL }

/** Classify the frontmost app for a focus session. DayView itself / blank is neutral. */
fun classifyFrontmost(
    frontmostBundleId: String?,
    onGoalBundleIds: Set<String>,
    dayViewBundleId: String = DAYVIEW_BUNDLE_ID,
): OnGoalState = when {
    frontmostBundleId.isNullOrBlank() -> OnGoalState.NEUTRAL
    frontmostBundleId == dayViewBundleId -> OnGoalState.NEUTRAL
    frontmostBundleId in onGoalBundleIds -> OnGoalState.ON_GOAL
    else -> OnGoalState.OFF_GOAL
}
