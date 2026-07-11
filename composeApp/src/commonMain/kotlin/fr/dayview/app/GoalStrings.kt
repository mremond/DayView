package fr.dayview.app

import androidx.compose.runtime.Composable
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.goal_deadline_reached
import fr.dayview.app.generated.resources.goal_hours_remaining
import fr.dayview.app.generated.resources.goal_less_than_an_hour
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration

/**
 * Renders the goal's remaining-working-time state to its localized label, e.g.
 * "Encore 12 h". Kept separate from [goalWorkingTime] so the calculation stays a plain,
 * unit-testable function while the wording lives in string resources.
 */
@Composable
fun goalWorkingTimeLabel(working: Duration, deadlineReached: Boolean): String = when (val state = goalWorkingTime(working, deadlineReached)) {
    GoalWorkingTime.DeadlineReached -> stringResource(Res.string.goal_deadline_reached)
    is GoalWorkingTime.HoursRemaining ->
        stringResource(Res.string.goal_hours_remaining, state.hours.toString())
    GoalWorkingTime.LessThanAnHour -> stringResource(Res.string.goal_less_than_an_hour)
}
