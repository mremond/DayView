package fr.dayview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.history_title
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import org.jetbrains.compose.resources.stringResource

/** One cell in the week grid: a day key, its short label, and its captured record (if any). */
internal data class HistoryWeekDay(val dayKey: Long, val label: String, val record: DayHistoryRecord?)

/** The 7 Monday→Sunday day keys of the calendar week containing [todayKey]. */
internal fun weekDaysEndingAt(todayKey: Long): List<Long> {
    val today = LocalDate.fromEpochDays(todayKey.toInt())
    val monday = today.toEpochDays() - (today.dayOfWeek.isoDayNumber - DayOfWeek.MONDAY.isoDayNumber)
    return (0..6).map { monday + it }
}

/**
 * The week overview grid: 7 [MiniRing]s, Monday→Sunday. Days with a captured record render
 * a clickable ring tagged [DayViewTestTags.historyDayCell]; days with no data render a
 * greyed, non-clickable placeholder carrying the same tag (so tests can assert on presence
 * without a click action, and no [MiniRing] is drawn for data that doesn't exist).
 */
@Composable
internal fun HistoryWeekScreen(
    days: List<HistoryWeekDay>,
    onSelectDay: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            for (day in days) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(day.label)
                    val record = day.record
                    if (record != null) {
                        val state = remember(record) { record.toFrozenUiState() }
                        MiniRing(
                            progress = state.dayProgress,
                            busyBlockArcs = state.busyBlockArcsState,
                            focusArcs = state.focusArcsState,
                            modifier = Modifier
                                .testTag(DayViewTestTags.historyDayCell(day.dayKey))
                                .clickable { onSelectDay(day.dayKey) },
                        )
                    } else {
                        // Greyed, non-clickable placeholder for a day with no capture: no
                        // MiniRing is drawn since there's no ring data to project.
                        Box(
                            modifier = Modifier
                                .testTag(DayViewTestTags.historyDayCell(day.dayKey))
                                .size(28.dp)
                                .background(colors.overlay.copy(alpha = .1f), CircleShape),
                        )
                    }
                }
            }
        }
    }
}
