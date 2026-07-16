package fr.dayview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.countdown_a11y_remaining
import fr.dayview.app.generated.resources.focused_today
import fr.dayview.app.generated.resources.history_date
import fr.dayview.app.generated.resources.history_day_a11y
import fr.dayview.app.generated.resources.history_day_busy_a11y
import fr.dayview.app.generated.resources.history_day_no_activity_a11y
import fr.dayview.app.generated.resources.history_day_no_data_a11y
import fr.dayview.app.generated.resources.history_open_day
import fr.dayview.app.generated.resources.history_title
import fr.dayview.app.generated.resources.settings_back
import fr.dayview.app.generated.resources.today_status_not_started
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
import kotlin.time.Duration

private val HistoryDayTargetSize = 48.dp
private val SingleRowWeekMinWidth = HistoryDayTargetSize * 7 + 8.dp * 6

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

@Composable
private fun historyDate(dayKey: Long): String {
    val date = LocalDate.fromEpochDays(dayKey.toInt())
    return stringResource(
        Res.string.history_date,
        date.year.toString().padStart(4, '0'),
        (date.month.ordinal + 1).toString().padStart(2, '0'),
        date.day.toString().padStart(2, '0'),
    )
}

private fun historyBusyDuration(state: DayViewUiState): Duration {
    if (!state.netTimeSettings.enabled) return Duration.ZERO
    val (windowStart, windowEnd) = state.dayWindow
    return busyWithinWindow(state.busyIntervalsToday, windowStart, windowEnd)
}

@Composable
private fun historyDayDescription(
    dayKey: Long,
    state: DayViewUiState,
): String {
    val parts = mutableListOf(stringResource(Res.string.history_day_a11y, historyDate(dayKey)))
    val progress = state.dayProgress
    when {
        !progress.hasStarted -> parts += stringResource(Res.string.today_status_not_started)
        !progress.isFinished -> parts += stringResource(
            Res.string.countdown_a11y_remaining,
            formatDurationHm(progress.remaining),
            progress.percentageRemaining,
        )
    }
    if (state.focusedToday > Duration.ZERO) {
        parts += stringResource(Res.string.focused_today, formatDurationHm(state.focusedToday))
    }
    val busyDuration = historyBusyDuration(state)
    if (busyDuration > Duration.ZERO) {
        parts += stringResource(Res.string.history_day_busy_a11y, formatDurationHm(busyDuration))
    }
    if (parts.size == 1) {
        parts += stringResource(Res.string.history_day_no_activity_a11y)
    }
    return parts.joinToString(" ")
}

@Composable
private fun HistoryDayCell(
    day: HistoryWeekDay,
    onSelectDay: (Long) -> Unit,
) {
    val colors = LocalDayViewColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(weekdayLabel(day.dayKey))
        val record = day.record
        if (record != null) {
            val state = remember(record, day.now) { record.toFrozenUiState(now = day.now) }
            val description = historyDayDescription(day.dayKey, state)
            Box(
                modifier = Modifier
                    .size(HistoryDayTargetSize)
                    .testTag(DayViewTestTags.historyDayCell(day.dayKey))
                    .semantics { contentDescription = description }
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(Res.string.history_open_day),
                        onClick = { onSelectDay(day.dayKey) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                MiniRing(
                    progress = state.dayProgress,
                    busyBlockArcs = state.busyBlockArcsState,
                    focusArcs = state.focusArcsState,
                )
            }
        } else {
            val description = stringResource(Res.string.history_day_no_data_a11y, historyDate(day.dayKey))
            Box(
                modifier = Modifier
                    .size(HistoryDayTargetSize)
                    .testTag(DayViewTestTags.historyDayCell(day.dayKey))
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.size(28.dp)
                        .background(colors.overlay.copy(alpha = .1f), CircleShape),
                )
            }
        }
    }
}

/**
 * The week overview grid: 7 [MiniRing]s, Monday→Sunday. Each 28 dp visual ring sits in a
 * 48 dp accessible target describing its date and useful statistics. Narrow windows wrap
 * the week into two rows rather than shrinking those targets. Days with no data render a
 * labelled, non-clickable placeholder carrying the same tag.
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
            backLabel = stringResource(Res.string.settings_back),
            backTestTag = DayViewTestTags.HistoryBack,
            onBack = onBack,
        )
        Spacer(Modifier.height(24.dp))
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().widthIn(max = 1040.dp),
        ) {
            val rows = if (maxWidth >= SingleRowWeekMinWidth) listOf(days) else days.chunked(4)
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                rows.forEach { rowDays ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        rowDays.forEach { day ->
                            HistoryDayCell(day, onSelectDay)
                        }
                    }
                }
            }
        }
    }
}
