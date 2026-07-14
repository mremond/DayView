package fr.dayview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.history_title
import org.jetbrains.compose.resources.stringResource

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
    val colors = LocalDayViewColors.current
    Column(
        modifier = modifier.fillMaxSize()
            .background(Brush.radialGradient(colors = listOf(colors.glow, colors.ink), radius = 950f))
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        ScreenTopBar(
            title = stringResource(Res.string.history_title),
            backTestTag = DayViewTestTags.HistoryBack,
            onBack = onBack,
        )
        Spacer(Modifier.height(24.dp))
        CountdownCircle(
            state.dayProgress,
            state.showSeconds,
            netTime = state.netTime,
            focusArcs = state.focusArcsState,
            focusedToday = state.focusedToday,
            sessionFocusedToday = state.sessionFocusedToday,
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
