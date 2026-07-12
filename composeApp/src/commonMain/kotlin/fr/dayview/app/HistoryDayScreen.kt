package fr.dayview.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Read-only replay of one archived day: the live ring, fed from the record's frozen
 * state, with no action panels (no starting focus or editing detours on a past day).
 */
@Composable
internal fun HistoryDayScreen(
    record: DayHistoryRecord,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = remember(record) { record.toFrozenUiState() }
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Text("‹", modifier = Modifier.testTag(DayViewTestTags.HistoryBack).clickable { onBack() })
        CountdownCircle(
            state.dayProgress,
            state.showSeconds,
            netTime = state.netTime,
            focusArcs = state.focusArcsState,
            focusedToday = state.focusedToday,
            windowStart = state.dayWindow.first,
            windowEnd = state.dayWindow.second,
            detourBodies = state.detourBodiesState,
            detoursTotal = state.detoursTotalToday,
            busyBlockArcs = state.busyBlockArcsState,
            cleanSessionsToday = state.cleanSessionsToday,
            streakDays = state.cleanStreakDays,
            hasGoal = state.goalTitle.isNotBlank() || state.goalDeadline != null,
            onOpenDetourList = null,
        )
    }
}
