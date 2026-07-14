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
import androidx.compose.foundation.layout.widthIn
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
import fr.dayview.app.generated.resources.weekday_fri
import fr.dayview.app.generated.resources.weekday_mon
import fr.dayview.app.generated.resources.weekday_sat
import fr.dayview.app.generated.resources.weekday_sun
import fr.dayview.app.generated.resources.weekday_thu
import fr.dayview.app.generated.resources.weekday_tue
import fr.dayview.app.generated.resources.weekday_wed
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Monday→Sunday short weekday labels, in the same order as [weekDaysEndingAt]. */
private val weekdayLabelResources: List<StringResource> = listOf(
    Res.string.weekday_mon,
    Res.string.weekday_tue,
    Res.string.weekday_wed,
    Res.string.weekday_thu,
    Res.string.weekday_fri,
    Res.string.weekday_sat,
    Res.string.weekday_sun,
)

/** The localized short label for the weekday of [dayKey] (an epoch-day count). */
@Composable
internal fun weekdayLabel(dayKey: Long): String {
    val isoDay = LocalDate.fromEpochDays(dayKey.toInt()).dayOfWeek.isoDayNumber
    return stringResource(weekdayLabelResources[isoDay - 1])
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
            .padding(horizontal = 48.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ScreenTopBar(
            title = stringResource(Res.string.history_title),
            backTestTag = DayViewTestTags.HistoryBack,
            onBack = onBack,
        )
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth().widthIn(max = 1040.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            for (day in days) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(weekdayLabel(day.dayKey))
                    val record = day.record
                    if (record != null) {
                        val state = remember(record, day.now) { record.toFrozenUiState(now = day.now) }
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
