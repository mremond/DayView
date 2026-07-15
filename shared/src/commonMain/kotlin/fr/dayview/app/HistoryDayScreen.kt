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
import kotlin.time.Instant

/**
 * Read-only replay of one day: the live ring, fed from the record's projected state, with no
 * action panels (no starting focus or editing detours from history). [now] projects today's
 * still-in-progress day at the live instant; leave it null for archived days so the ring
 * freezes at the day's end.
 */
@Composable
internal fun HistoryDayScreen(
    record: DayHistoryRecord,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    now: Instant? = null,
) {
    val state = remember(record, now) { record.toFrozenUiState(now = now) }
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
