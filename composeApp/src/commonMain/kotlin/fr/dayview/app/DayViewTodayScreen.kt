package fr.dayview.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.app_wordmark
import fr.dayview.app.generated.resources.busy_generic
import fr.dayview.app.generated.resources.busy_remaining
import fr.dayview.app.generated.resources.busy_time_range
import fr.dayview.app.generated.resources.countdown_day_over
import fr.dayview.app.generated.resources.countdown_time_left
import fr.dayview.app.generated.resources.day_available_percent
import fr.dayview.app.generated.resources.dialog_cancel
import fr.dayview.app.generated.resources.dialog_ok
import fr.dayview.app.generated.resources.focus_break_conscious
import fr.dayview.app.generated.resources.focus_break_disconnect
import fr.dayview.app.generated.resources.focus_break_since
import fr.dayview.app.generated.resources.focus_close_section
import fr.dayview.app.generated.resources.focus_closed
import fr.dayview.app.generated.resources.focus_drift_dismiss
import fr.dayview.app.generated.resources.focus_drift_title
import fr.dayview.app.generated.resources.focus_duration_decrease
import fr.dayview.app.generated.resources.focus_duration_increase
import fr.dayview.app.generated.resources.focus_duration_value
import fr.dayview.app.generated.resources.focus_intention_hint
import fr.dayview.app.generated.resources.focus_intention_label
import fr.dayview.app.generated.resources.focus_intention_placeholder
import fr.dayview.app.generated.resources.focus_intention_prompt
import fr.dayview.app.generated.resources.focus_my_intention
import fr.dayview.app.generated.resources.focus_outcome_completed
import fr.dayview.app.generated.resources.focus_outcome_progressed
import fr.dayview.app.generated.resources.focus_outcome_to_resume
import fr.dayview.app.generated.resources.focus_resume
import fr.dayview.app.generated.resources.focus_resume_button
import fr.dayview.app.generated.resources.focus_resume_point
import fr.dayview.app.generated.resources.focus_resume_time_left
import fr.dayview.app.generated.resources.focus_section
import fr.dayview.app.generated.resources.focus_single_thing
import fr.dayview.app.generated.resources.focus_start_button
import fr.dayview.app.generated.resources.focus_start_full_button
import fr.dayview.app.generated.resources.focus_state_active
import fr.dayview.app.generated.resources.focus_state_break_active
import fr.dayview.app.generated.resources.focus_state_idle
import fr.dayview.app.generated.resources.focus_state_series_inactive
import fr.dayview.app.generated.resources.focus_stop
import fr.dayview.app.generated.resources.focused_today
import fr.dayview.app.generated.resources.goal_badge
import fr.dayview.app.generated.resources.goal_choose_date
import fr.dayview.app.generated.resources.goal_deadline_label
import fr.dayview.app.generated.resources.goal_define
import fr.dayview.app.generated.resources.goal_define_deadline
import fr.dayview.app.generated.resources.goal_invalid_time
import fr.dayview.app.generated.resources.goal_progress_percent
import fr.dayview.app.generated.resources.goal_section_title
import fr.dayview.app.generated.resources.goal_start_before_deadline
import fr.dayview.app.generated.resources.goal_start_label
import fr.dayview.app.generated.resources.goal_title_label
import fr.dayview.app.generated.resources.goal_title_placeholder
import fr.dayview.app.generated.resources.mini_window_button
import fr.dayview.app.generated.resources.minutes_label
import fr.dayview.app.generated.resources.net_remaining
import fr.dayview.app.generated.resources.seconds_remaining
import fr.dayview.app.generated.resources.settings_title
import fr.dayview.app.generated.resources.today_hero_ending
import fr.dayview.app.generated.resources.today_hero_finished
import fr.dayview.app.generated.resources.today_hero_not_started
import fr.dayview.app.generated.resources.today_hero_ongoing
import fr.dayview.app.generated.resources.today_status_ending
import fr.dayview.app.generated.resources.today_status_finished
import fr.dayview.app.generated.resources.today_status_not_started
import fr.dayview.app.generated.resources.today_status_ongoing
import org.jetbrains.compose.resources.stringResource
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal data class DayViewScreenActions(
    val openSettings: () -> Unit,
    val openMiniWindow: (() -> Unit)?,
    val changeGoalTitle: (String) -> Unit,
    val changeGoalDeadline: (String) -> Unit,
    val commitGoalDeadline: () -> Unit,
    val changeGoalStart: (String) -> Unit,
    val commitGoalStart: () -> Unit,
    val changeFocusIntention: (String) -> Unit,
    val changePomodoroDuration: (Int) -> Unit,
    val startPomodoro: () -> Unit,
    val stopPomodoro: () -> Unit,
    val closePomodoro: (FocusClosureOutcome) -> Unit,
)

internal data class FocusReminderUiState(
    val showDriftReminder: Boolean,
    val dismissDriftReminder: () -> Unit,
    val showResumeRitual: Boolean,
    val dismissResumeRitual: () -> Unit,
)

@Composable
internal fun DayViewScreen(
    state: DayViewUiState,
    actions: DayViewScreenActions,
    reminders: FocusReminderUiState,
) {
    val colors = LocalDayViewColors.current
    val progress = state.dayProgress
    val pomodoro = state.pomodoroProgress
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(colors.glow, colors.ink),
                    radius = 950f,
                ),
            )
            .safeDrawingPadding()
            .imePadding(),
    ) {
        val wide = maxWidth >= 780.dp
        val compactCountdownHeight = (maxWidth - 48.dp).coerceIn(240.dp, 320.dp)
        val pageModifier = if (wide) {
            Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)
        } else {
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp)
        }
        Column(
            modifier = pageModifier,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Header(actions.openSettings, actions.openMiniWindow)
            Spacer(Modifier.height(if (wide) 28.dp else 18.dp))

            if (wide) {
                Row(
                    modifier = Modifier.fillMaxSize().widthIn(max = 1040.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(64.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1.15f).fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CountdownCircle(
                            progress,
                            state.showSeconds,
                            Modifier.weight(1f).fillMaxWidth(),
                            busyArcs = state.busyArcsState,
                            netTime = state.netTime,
                            focusArcs = state.focusArcsState,
                            focusedToday = state.focusedToday,
                            windowStart = state.dayWindow.first,
                            windowEnd = state.dayWindow.second,
                        )
                        Spacer(Modifier.height(12.dp))
                        GlobalGoalPanel(
                            title = state.goalTitle,
                            deadline = state.goalDeadline,
                            start = state.goalStart,
                            now = state.now,
                            workStartMinutes = progress.startHour * 60 + progress.startMinute,
                            workEndMinutes = progress.endHour * 60 + progress.endMinute,
                            onTitleChange = actions.changeGoalTitle,
                            onDeadlineChange = actions.changeGoalDeadline,
                            onDeadlineCommit = actions.commitGoalDeadline,
                            onStartChange = actions.changeGoalStart,
                            onStartCommit = actions.commitGoalStart,
                        )
                    }
                    SidePanel(
                        progress = progress,
                        pomodoro = pomodoro,
                        focusIntention = state.focusIntention,
                        lastFocusClosure = state.lastFocusClosure,
                        onFocusIntentionChange = actions.changeFocusIntention,
                        showFocusDriftReminder = reminders.showDriftReminder,
                        onDismissFocusDriftReminder = reminders.dismissDriftReminder,
                        showFocusResumeRitual = reminders.showResumeRitual,
                        onDismissFocusResumeRitual = reminders.dismissResumeRitual,
                        onPomodoroDurationChange = actions.changePomodoroDuration,
                        onPomodoroStart = actions.startPomodoro,
                        onPomodoroStop = actions.stopPomodoro,
                        onPomodoroClose = actions.closePomodoro,
                        modifier = Modifier.weight(.85f).verticalScroll(rememberScrollState()),
                    )
                }
            } else {
                CountdownCircle(
                    progress,
                    state.showSeconds,
                    Modifier.fillMaxWidth().height(compactCountdownHeight),
                    busyArcs = state.busyArcsState,
                    netTime = state.netTime,
                    focusArcs = state.focusArcsState,
                    focusedToday = state.focusedToday,
                    windowStart = state.dayWindow.first,
                    windowEnd = state.dayWindow.second,
                )
                Spacer(Modifier.height(12.dp))
                CompactTodayContent(
                    state = state,
                    actions = actions,
                    reminders = reminders,
                )
            }
        }
    }
}

private enum class CompactSheet { FOCUS, GOAL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactTodayContent(
    state: DayViewUiState,
    actions: DayViewScreenActions,
    reminders: FocusReminderUiState,
) {
    val colors = LocalDayViewColors.current
    val progress = state.dayProgress
    val pomodoro = state.pomodoroProgress
    var openSheet by remember { mutableStateOf<CompactSheet?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = when {
                !progress.hasStarted -> stringResource(Res.string.today_status_not_started)
                progress.isFinished -> stringResource(Res.string.today_status_finished)
                progress.remainingRatio < .2f -> stringResource(Res.string.today_status_ending)
                else -> stringResource(Res.string.today_status_ongoing)
            },
            color = colors.muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))

        CompactGoalRow(
            title = state.goalTitle,
            deadline = state.goalDeadline,
            start = state.goalStart,
            now = state.now,
            workStartMinutes = progress.startHour * 60 + progress.startMinute,
            workEndMinutes = progress.endHour * 60 + progress.endMinute,
            onClick = { openSheet = CompactSheet.GOAL },
        )
        Spacer(Modifier.height(14.dp))

        if (pomodoro.status == PomodoroStatus.IDLE) {
            FocusEntryButton(
                lastClosure = state.lastFocusClosure,
                onClick = { openSheet = CompactSheet.FOCUS },
            )
        } else {
            FocusPanel(
                progress = pomodoro,
                intention = state.focusIntention,
                lastClosure = state.lastFocusClosure,
                onIntentionChange = actions.changeFocusIntention,
                showDriftReminder = reminders.showDriftReminder,
                onDismissDriftReminder = reminders.dismissDriftReminder,
                showResumeRitual = reminders.showResumeRitual,
                onDismissResumeRitual = reminders.dismissResumeRitual,
                onDurationChange = actions.changePomodoroDuration,
                onStart = actions.startPomodoro,
                onStop = actions.stopPomodoro,
                onClose = actions.closePomodoro,
            )
        }
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (progress.isFinished) colors.red else colors.mint, CircleShape))
            Spacer(Modifier.width(9.dp))
            Text(
                stringResource(Res.string.day_available_percent, (if (progress.isFinished) 0 else progress.percentageRemaining).toString()),
                color = colors.muted,
                fontSize = 12.sp,
            )
        }
    }

    if (openSheet == CompactSheet.FOCUS) {
        ModalBottomSheet(
            onDismissRequest = { openSheet = null },
            sheetState = rememberModalBottomSheetState(),
            containerColor = colors.panel,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp)
                    .imePadding(),
            ) {
                Text(stringResource(Res.string.focus_section), color = colors.amber, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                Spacer(Modifier.height(14.dp))
                FocusCreationContent(
                    progress = pomodoro,
                    intention = state.focusIntention,
                    lastClosure = state.lastFocusClosure,
                    onIntentionChange = actions.changeFocusIntention,
                    onDurationChange = actions.changePomodoroDuration,
                    onStart = {
                        actions.startPomodoro()
                        openSheet = null
                    },
                )
            }
        }
    }

    if (openSheet == CompactSheet.GOAL) {
        ModalBottomSheet(
            onDismissRequest = {
                actions.commitGoalDeadline()
                actions.commitGoalStart()
                openSheet = null
            },
            sheetState = rememberModalBottomSheetState(),
            containerColor = colors.panel,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp)
                    .imePadding(),
            ) {
                GoalEditorContent(
                    title = state.goalTitle,
                    deadline = state.goalDeadline,
                    start = state.goalStart,
                    now = state.now,
                    onTitleChange = actions.changeGoalTitle,
                    onDeadlineChange = actions.changeGoalDeadline,
                    onDeadlineCommit = actions.commitGoalDeadline,
                    onStartChange = actions.changeGoalStart,
                    onStartCommit = actions.commitGoalStart,
                )
            }
        }
    }
}

@Composable
private fun CompactGoalRow(
    title: String,
    deadline: Instant?,
    start: Instant?,
    now: Instant,
    workStartMinutes: Int,
    workEndMinutes: Int,
    onClick: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    val hasGoal = title.isNotBlank() || deadline != null
    val working = remember(now.toEpochMilliseconds() / 60_000, deadline, workStartMinutes, workEndMinutes) {
        deadline?.let {
            calculateGoalWorkingTime(
                now = now,
                deadline = it,
                startMinutesOfDay = workStartMinutes,
                endMinutesOfDay = workEndMinutes,
            )
        } ?: Duration.ZERO
    }
    Column(
        modifier = Modifier.widthIn(max = 430.dp).fillMaxWidth()
            .background(colors.panel, RoundedCornerShape(14.dp))
            .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (hasGoal) {
                Text(stringResource(Res.string.goal_badge), color = colors.mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                Spacer(Modifier.width(12.dp))
                Text(
                    formatGoalSummaryLine(
                        title = title,
                        workingHoursLabel = deadline?.let { goalWorkingTimeLabel(working, it <= now) },
                    ),
                    color = colors.cloud,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    stringResource(Res.string.goal_define),
                    color = colors.muted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (deadline != null) {
            Spacer(Modifier.height(10.dp))
            GoalProgressBar(
                now = now,
                start = start ?: now,
                deadline = deadline,
                workStartMinutes = workStartMinutes,
                workEndMinutes = workEndMinutes,
                animationLabel = "goal-progress-compact",
            )
        }
    }
}

@Composable
private fun FocusEntryButton(
    lastClosure: FocusClosureOutcome?,
    onClick: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    Column(
        modifier = Modifier.widthIn(max = 430.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (lastClosure != null) {
            FocusClosureChip(lastClosure)
        }
        FocusActionButton(
            stringResource(Res.string.focus_start_button),
            colors.amber,
            modifier = Modifier.fillMaxWidth(),
            filled = true,
            onClick = onClick,
        )
    }
}

@Composable
private fun FocusClosureChip(outcome: FocusClosureOutcome) {
    val colors = LocalDayViewColors.current
    val closureLabel = when (outcome) {
        FocusClosureOutcome.COMPLETED -> stringResource(Res.string.focus_outcome_completed)
        FocusClosureOutcome.PROGRESSED -> stringResource(Res.string.focus_outcome_progressed)
        FocusClosureOutcome.TO_RESUME -> stringResource(Res.string.focus_outcome_to_resume)
    }
    Text(
        stringResource(Res.string.focus_closed, closureLabel),
        color = colors.mint,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = .9.sp,
    )
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun GoalEditorContent(
    title: String,
    deadline: Instant?,
    start: Instant?,
    now: Instant,
    onTitleChange: (String) -> Unit,
    onDeadlineChange: (String) -> Unit,
    onDeadlineCommit: () -> Unit,
    onStartChange: (String) -> Unit,
    onStartCommit: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    Text(stringResource(Res.string.goal_section_title), color = colors.mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
    Spacer(Modifier.height(12.dp))
    GoalTextField(
        value = title,
        semanticLabel = stringResource(Res.string.goal_title_label),
        placeholder = stringResource(Res.string.goal_title_placeholder),
        onValueChange = onTitleChange,
        imeAction = ImeAction.Next,
    )
    Spacer(Modifier.height(9.dp))
    GoalDateRow(
        deadline = deadline,
        start = start,
        now = now,
        onDeadlineChange = onDeadlineChange,
        onDeadlineCommit = onDeadlineCommit,
        onStartChange = onStartChange,
        onStartCommit = onStartCommit,
    )
}

@Composable
private fun Header(onOpenSettings: () -> Unit, onOpenMiniWindow: (() -> Unit)?) {
    val colors = LocalDayViewColors.current
    Row(
        modifier = Modifier.fillMaxWidth().widthIn(max = 1040.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(colors.mint, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(Res.string.app_wordmark), color = colors.cloud, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.2.sp)
        Spacer(Modifier.weight(1f))
        onOpenMiniWindow?.let {
            Text(
                stringResource(Res.string.mini_window_button),
                color = colors.muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.4.sp,
                modifier = Modifier.minimumInteractiveComponentSize()
                    .clickable(role = Role.Button, onClick = it)
                    .padding(vertical = 10.dp, horizontal = 4.dp),
            )
            Spacer(Modifier.width(18.dp))
        }
        Text(
            stringResource(Res.string.settings_title),
            color = colors.muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.4.sp,
            modifier = Modifier.minimumInteractiveComponentSize()
                .clickable(role = Role.Button, onClick = onOpenSettings)
                .padding(vertical = 10.dp, horizontal = 4.dp),
        )
    }
}

@Composable
internal fun CountdownCircle(
    progress: DayProgress,
    showSeconds: Boolean,
    modifier: Modifier = Modifier,
    busyArcs: List<BusyArc> = emptyList(),
    netTime: NetTime? = null,
    focusArcs: List<FocusArc> = emptyList(),
    focusedToday: Duration = Duration.ZERO,
    windowStart: Instant = Instant.fromEpochMilliseconds(0L),
    windowEnd: Instant = Instant.fromEpochMilliseconds(0L),
) {
    val colors = LocalDayViewColors.current
    val animatedRemaining by animateFloatAsState(progress.remainingRatio, tween(650), label = "remaining")
    val accent by animateColorAsState(
        when {
            progress.isFinished -> colors.red
            progress.remainingRatio < .2f -> colors.amber
            else -> colors.mint
        },
        label = "accent",
    )
    var hoveredBusy by remember { mutableStateOf<HoveredBusyArc?>(null) }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val circleSize = minOf(maxWidth, maxHeight, 510.dp)
            val circleModifier = if (busyArcs.isEmpty()) {
                Modifier.size(circleSize)
            } else {
                Modifier.size(circleSize).pointerInput(busyArcs) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val position = event.changes.firstOrNull()?.position
                            if (event.type == PointerEventType.Exit || position == null) {
                                hoveredBusy = null
                            } else if (
                                event.type == PointerEventType.Move ||
                                event.type == PointerEventType.Enter
                            ) {
                                hoveredBusy = hitTestBusyArc(position, size.width, size.height, busyArcs)
                                    ?.let { HoveredBusyArc(it, position) }
                            }
                        }
                    }
                }
            }
            Box(circleModifier, contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val strokeWidth = size.minDimension * .055f
                    val inset = strokeWidth / 2 + 4.dp.toPx()
                    val arcSize = Size(size.width - inset * 2, size.height - inset * 2)

                    drawArc(
                        color = colors.overlay.copy(alpha = .075f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(strokeWidth, cap = StrokeCap.Round),
                    )

                    busyArcs.forEach { arc ->
                        drawArc(
                            color = colors.overlay.copy(alpha = .35f),
                            startAngle = arc.startAngleDegrees,
                            sweepAngle = arc.sweepDegrees,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(strokeWidth, cap = StrokeCap.Butt),
                        )
                    }

                    focusArcs.forEach { arc ->
                        drawArc(
                            color = colors.mint.copy(alpha = .55f),
                            startAngle = arc.startAngleDegrees,
                            sweepAngle = arc.sweepDegrees,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(strokeWidth * .5f, cap = StrokeCap.Round),
                        )
                    }

                    repeat(24) { index ->
                        val angle = Math.toRadians(index * 15.0 - 90.0)
                        val outer = size.minDimension / 2 - 1.dp.toPx()
                        val inner = outer - if (index % 6 == 0) 10.dp.toPx() else 5.dp.toPx()
                        val center = Offset(size.width / 2, size.height / 2)
                        drawLine(
                            color = colors.overlay.copy(alpha = if (index % 6 == 0) .28f else .12f),
                            start = center + Offset((kotlin.math.cos(angle) * inner).toFloat(), (kotlin.math.sin(angle) * inner).toFloat()),
                            end = center + Offset((kotlin.math.cos(angle) * outer).toFloat(), (kotlin.math.sin(angle) * outer).toFloat()),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }

                    if (animatedRemaining > 0f) {
                        val momentAngle = currentMomentAngleDegrees(animatedRemaining)
                        // Before the day starts the ring is complete and "intact": draw it in a
                        // uniform colour. The sweep gradient only makes sense once there is a
                        // leading edge (the moment marker) to give relief; on a full 360° ring it
                        // would surface a visible seam where its two ends meet.
                        if (progress.hasStarted) {
                            // A sweep gradient is anchored to the canvas (it ramps from its first
                            // colour at 0° clockwise to its last colour at 360°, with a hard seam
                            // where the two ends meet). Rotate the draw scope by the moment angle so
                            // that seam coincides with the arc's own start — the gap in the ring —
                            // instead of falling mid-arc as a visible band. The colour then ramps
                            // smoothly from the bright leading edge to a dimmer tail.
                            rotate(momentAngle, pivot = Offset(size.width / 2f, size.height / 2f)) {
                                drawArc(
                                    brush = Brush.sweepGradient(listOf(accent, accent.copy(alpha = .62f))),
                                    startAngle = 0f,
                                    sweepAngle = animatedRemaining * 360f,
                                    useCenter = false,
                                    topLeft = Offset(inset, inset),
                                    size = arcSize,
                                    style = Stroke(strokeWidth, cap = StrokeCap.Round),
                                )
                            }
                        } else {
                            drawArc(
                                color = accent,
                                startAngle = momentAngle,
                                sweepAngle = animatedRemaining * 360f,
                                useCenter = false,
                                topLeft = Offset(inset, inset),
                                size = arcSize,
                                style = Stroke(strokeWidth, cap = StrokeCap.Round),
                            )
                        }

                        if (progress.hasStarted && !progress.isFinished) {
                            val angleRadians = Math.toRadians(momentAngle.toDouble())
                            val arcRadius = arcSize.width / 2f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val markerCenter = center + Offset(
                                x = (kotlin.math.cos(angleRadians) * arcRadius).toFloat(),
                                y = (kotlin.math.sin(angleRadians) * arcRadius).toFloat(),
                            )
                            drawCircle(
                                color = colors.amber.copy(alpha = .2f),
                                radius = strokeWidth * .68f,
                                center = markerCenter,
                            )
                            drawCircle(
                                color = colors.amber,
                                radius = strokeWidth * .4f,
                                center = markerCenter,
                            )
                            drawCircle(
                                color = Color.White.copy(alpha = .45f),
                                radius = strokeWidth * .1f,
                                center = markerCenter - Offset(strokeWidth * .1f, strokeWidth * .1f),
                            )
                        }
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (progress.isFinished) stringResource(Res.string.countdown_day_over) else stringResource(Res.string.countdown_time_left),
                        color = if (progress.isFinished) colors.red else colors.muted,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.8.sp,
                        textAlign = TextAlign.Center,
                    )
                    if (!progress.isFinished) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(progress.remainingHours.toString().padStart(2, '0'), color = colors.cloud, fontSize = 52.sp, fontWeight = FontWeight.Light)
                            Text("h", color = colors.muted, fontSize = 19.sp, modifier = Modifier.padding(bottom = 9.dp, start = 3.dp, end = 7.dp))
                            Text(progress.remainingMinutes.toString().padStart(2, '0'), color = colors.cloud, fontSize = 52.sp, fontWeight = FontWeight.Light)
                        }
                        if (showSeconds) {
                            Text(
                                stringResource(Res.string.seconds_remaining, progress.remainingSeconds.toString().padStart(2, '0')),
                                color = colors.muted,
                                fontSize = 12.sp,
                                letterSpacing = .8.sp,
                            )
                        }
                        if (netTime != null && netTime.busyRemaining > Duration.ZERO) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(Res.string.net_remaining, formatDurationHm(netTime.netRemaining)),
                                color = colors.mint,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = .5.sp,
                            )
                            Text(
                                stringResource(Res.string.busy_remaining, formatDurationHm(netTime.busyRemaining)),
                                color = colors.muted,
                                fontSize = 11.sp,
                                letterSpacing = .5.sp,
                            )
                        }
                        if (focusedToday > Duration.ZERO) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(Res.string.focused_today, formatDurationHm(focusedToday)),
                                color = colors.mint,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = .5.sp,
                            )
                        }
                    }
                }

                hoveredBusy?.let { hovered ->
                    val arc = hovered.arc
                    val startLabel = formatClockHm(
                        angleToInstant(arc.startAngleDegrees, windowStart, windowEnd),
                    )
                    val endLabel = formatClockHm(
                        angleToInstant(
                            arc.startAngleDegrees + arc.sweepDegrees,
                            windowStart,
                            windowEnd,
                        ),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset {
                                IntOffset(
                                    hovered.position.x.roundToInt() + 14,
                                    hovered.position.y.roundToInt() + 14,
                                )
                            }
                            .background(colors.panel, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Column {
                            val titles = arc.titles.filter { it.isNotBlank() }
                            if (titles.isEmpty()) {
                                Text(stringResource(Res.string.busy_generic), color = colors.cloud, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            } else {
                                titles.forEach { title ->
                                    Text(title, color = colors.cloud, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                            Text(
                                stringResource(Res.string.busy_time_range, startLabel, endLabel),
                                color = colors.muted,
                                fontSize = 11.sp,
                                letterSpacing = .5.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class HoveredBusyArc(val arc: BusyArc, val position: Offset)

/** Renvoie l'arc occupé sous le pointeur, ou null si le pointeur n'est pas sur l'anneau. */
private fun hitTestBusyArc(
    position: Offset,
    width: Int,
    height: Int,
    busyArcs: List<BusyArc>,
): BusyArc? {
    val cx = width / 2f
    val cy = height / 2f
    val dx = position.x - cx
    val dy = position.y - cy
    val radiusFraction = hypot(dx, dy) / (minOf(width, height) / 2f)
    if (radiusFraction !in 0.70f..1.02f) return null
    val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    return busyArcs.firstOrNull { arcContainsAngle(it, angle) }
}

@Composable
private fun SidePanel(
    progress: DayProgress,
    pomodoro: PomodoroProgress,
    focusIntention: String,
    lastFocusClosure: FocusClosureOutcome?,
    onFocusIntentionChange: (String) -> Unit,
    showFocusDriftReminder: Boolean,
    onDismissFocusDriftReminder: () -> Unit,
    showFocusResumeRitual: Boolean,
    onDismissFocusResumeRitual: () -> Unit,
    onPomodoroDurationChange: (Int) -> Unit,
    onPomodoroStart: () -> Unit,
    onPomodoroStop: () -> Unit,
    onPomodoroClose: (FocusClosureOutcome) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDayViewColors.current
    Column(modifier = modifier.widthIn(max = 430.dp)) {
        Text(
            text = when {
                !progress.hasStarted -> stringResource(Res.string.today_hero_not_started)
                progress.isFinished -> stringResource(Res.string.today_hero_finished)
                progress.remainingRatio < .2f -> stringResource(Res.string.today_hero_ending)
                else -> stringResource(Res.string.today_hero_ongoing)
            },
            color = colors.cloud,
            fontSize = 22.sp,
            lineHeight = 29.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(22.dp))

        FocusPanel(
            progress = pomodoro,
            intention = focusIntention,
            lastClosure = lastFocusClosure,
            onIntentionChange = onFocusIntentionChange,
            showDriftReminder = showFocusDriftReminder,
            onDismissDriftReminder = onDismissFocusDriftReminder,
            showResumeRitual = showFocusResumeRitual,
            onDismissResumeRitual = onDismissFocusResumeRitual,
            onDurationChange = onPomodoroDurationChange,
            onStart = onPomodoroStart,
            onStop = onPomodoroStop,
            onClose = onPomodoroClose,
        )
        Spacer(Modifier.height(18.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (progress.isFinished) colors.red else colors.mint, CircleShape))
            Spacer(Modifier.width(9.dp))
            Text(
                stringResource(Res.string.day_available_percent, (if (progress.isFinished) 0 else progress.percentageRemaining).toString()),
                color = colors.muted,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun FocusPanel(
    progress: PomodoroProgress,
    intention: String,
    lastClosure: FocusClosureOutcome?,
    onIntentionChange: (String) -> Unit,
    showDriftReminder: Boolean,
    onDismissDriftReminder: () -> Unit,
    showResumeRitual: Boolean,
    onDismissResumeRitual: () -> Unit,
    onDurationChange: (Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClose: (FocusClosureOutcome) -> Unit,
) {
    val colors = LocalDayViewColors.current
    val animatedRatio by animateFloatAsState(progress.remainingRatio, tween(500), label = "focus-progress")
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(colors.panel, RoundedCornerShape(18.dp))
            .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.focus_section), color = colors.amber, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
            Spacer(Modifier.weight(1f))
            Text(
                when (progress.status) {
                    PomodoroStatus.IDLE -> stringResource(Res.string.focus_state_idle)
                    PomodoroStatus.ACTIVE -> stringResource(Res.string.focus_state_active)
                    PomodoroStatus.BREAK -> if (progress.breakElapsed >= 60.minutes) {
                        stringResource(Res.string.focus_state_series_inactive)
                    } else {
                        stringResource(Res.string.focus_state_break_active)
                    }
                },
                color = if (progress.status == PomodoroStatus.BREAK) colors.mint else colors.muted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = .7.sp,
            )
        }
        Spacer(Modifier.height(12.dp))

        if (progress.status == PomodoroStatus.ACTIVE) {
            if (showResumeRitual) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .background(colors.mint.copy(alpha = .12f), RoundedCornerShape(12.dp))
                        .border(1.dp, colors.mint.copy(alpha = .3f), RoundedCornerShape(12.dp))
                        .padding(13.dp),
                ) {
                    Text(
                        stringResource(Res.string.focus_resume_point),
                        color = colors.mint,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        intention.ifBlank { stringResource(Res.string.focus_single_thing) },
                        color = colors.cloud,
                        fontSize = 16.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        stringResource(Res.string.focus_resume_time_left, formatPomodoroClock(progress)),
                        color = colors.muted,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(11.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        FocusActionButton(
                            stringResource(Res.string.focus_stop),
                            colors.red,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onStop()
                                onDismissResumeRitual()
                            },
                        )
                        FocusActionButton(
                            stringResource(Res.string.focus_resume),
                            colors.mint,
                            modifier = Modifier.weight(1f),
                            filled = true,
                            onClick = onDismissResumeRitual,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            } else if (showDriftReminder) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .background(colors.amber.copy(alpha = .12f), RoundedCornerShape(12.dp))
                        .border(1.dp, colors.amber.copy(alpha = .3f), RoundedCornerShape(12.dp))
                        .padding(13.dp),
                ) {
                    Text(
                        stringResource(Res.string.focus_drift_title),
                        color = colors.amber,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        intention.ifBlank { stringResource(Res.string.focus_single_thing) },
                        color = colors.cloud,
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(10.dp))
                    FocusActionButton(stringResource(Res.string.focus_drift_dismiss), colors.amber, onClick = onDismissDriftReminder)
                }
                Spacer(Modifier.height(12.dp))
            }
            Text(
                stringResource(Res.string.focus_my_intention),
                color = colors.muted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                intention.ifBlank { stringResource(Res.string.focus_single_thing) },
                color = colors.cloud,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatPomodoroClock(progress), color = colors.cloud, fontSize = 34.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.weight(1f))
                FocusActionButton(stringResource(Res.string.focus_stop), colors.red, onClick = onStop)
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().height(5.dp)
                    .background(colors.overlay.copy(alpha = .08f), CircleShape),
            ) {
                Box(
                    Modifier.fillMaxWidth(animatedRatio.coerceIn(0f, 1f)).height(5.dp)
                        .background(colors.amber, CircleShape),
                )
            }
        } else if (progress.status == PomodoroStatus.BREAK) {
            Text(
                if (progress.breakElapsed >= 60.minutes) stringResource(Res.string.focus_state_series_inactive) else stringResource(Res.string.focus_break_since),
                color = colors.muted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(5.dp))
            Text(formatBreakClock(progress), color = colors.cloud, fontSize = 34.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(14.dp))
            Text(
                if (progress.breakElapsed < 10.minutes) {
                    stringResource(Res.string.focus_break_disconnect)
                } else {
                    stringResource(Res.string.focus_break_conscious)
                },
                color = colors.muted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = .9.sp,
            )
            Spacer(Modifier.height(9.dp))
            FocusActionButton(
                stringResource(Res.string.focus_resume_button),
                colors.mint,
                modifier = Modifier.fillMaxWidth(),
                filled = true,
                onClick = onStart,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(Res.string.focus_close_section),
                color = colors.muted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FocusActionButton(
                    stringResource(Res.string.focus_outcome_completed),
                    colors.mint,
                    modifier = Modifier.weight(1f),
                    onClick = { onClose(FocusClosureOutcome.COMPLETED) },
                )
                FocusActionButton(
                    stringResource(Res.string.focus_outcome_progressed),
                    colors.amber,
                    modifier = Modifier.weight(1f),
                    onClick = { onClose(FocusClosureOutcome.PROGRESSED) },
                )
                FocusActionButton(
                    stringResource(Res.string.focus_outcome_to_resume),
                    colors.muted,
                    modifier = Modifier.weight(1f),
                    onClick = { onClose(FocusClosureOutcome.TO_RESUME) },
                )
            }
        } else {
            FocusCreationContent(
                progress = progress,
                intention = intention,
                lastClosure = lastClosure,
                onIntentionChange = onIntentionChange,
                onDurationChange = onDurationChange,
                onStart = onStart,
            )
        }
    }
}

@Composable
private fun FocusCreationContent(
    progress: PomodoroProgress,
    intention: String,
    lastClosure: FocusClosureOutcome?,
    onIntentionChange: (String) -> Unit,
    onDurationChange: (Int) -> Unit,
    onStart: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    if (lastClosure != null) {
        FocusClosureChip(lastClosure)
    }
    Text(
        stringResource(Res.string.focus_intention_prompt),
        color = colors.muted,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )
    Spacer(Modifier.height(7.dp))
    GoalTextField(
        value = intention,
        semanticLabel = stringResource(Res.string.focus_intention_label),
        placeholder = stringResource(Res.string.focus_intention_placeholder),
        onValueChange = onIntentionChange,
    )
    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        TimeButton(
            label = "−",
            enabled = progress.durationMinutes > 5,
            onClickLabel = stringResource(Res.string.focus_duration_decrease),
            valueDescription = stringResource(Res.string.focus_duration_value, progress.durationMinutes.toString()),
        ) { onDurationChange(-5) }
        Spacer(Modifier.width(18.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(progress.durationMinutes.toString(), color = colors.cloud, fontSize = 28.sp, fontWeight = FontWeight.Light)
            Text(stringResource(Res.string.minutes_label), color = colors.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        Spacer(Modifier.width(18.dp))
        TimeButton(
            label = "+",
            enabled = progress.durationMinutes < 180,
            onClickLabel = stringResource(Res.string.focus_duration_increase),
            valueDescription = stringResource(Res.string.focus_duration_value, progress.durationMinutes.toString()),
        ) { onDurationChange(5) }
    }
    Spacer(Modifier.height(13.dp))
    FocusActionButton(
        stringResource(Res.string.focus_start_full_button),
        colors.amber,
        modifier = Modifier.fillMaxWidth(),
        enabled = intention.isNotBlank(),
        filled = true,
        onClick = onStart,
    )
    if (intention.isBlank()) {
        Spacer(Modifier.height(7.dp))
        Text(stringResource(Res.string.focus_intention_hint), color = colors.muted, fontSize = 10.sp)
    }
}

@Composable
internal fun FocusActionButton(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    val backgroundColor = when {
        !enabled -> color.copy(alpha = .05f)
        filled -> color.copy(alpha = .92f)
        else -> color.copy(alpha = .14f)
    }
    val contentColor = when {
        !enabled -> color.copy(alpha = .4f)
        filled -> colors.ink
        else -> color
    }
    Box(
        modifier = modifier.minimumInteractiveComponentSize()
            .background(backgroundColor, RoundedCornerShape(12.dp))
            .border(1.dp, color.copy(alpha = if (enabled) .38f else .1f), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = contentColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = .8.sp,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun GlobalGoalPanel(
    title: String,
    deadline: Instant?,
    start: Instant?,
    now: Instant,
    workStartMinutes: Int,
    workEndMinutes: Int,
    onTitleChange: (String) -> Unit,
    onDeadlineChange: (String) -> Unit,
    onDeadlineCommit: () -> Unit,
    onStartChange: (String) -> Unit,
    onStartCommit: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(colors.panel, RoundedCornerShape(18.dp))
            .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.goal_section_title), color = colors.mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
            Spacer(Modifier.weight(1f))
            if (deadline != null) {
                val working = remember(
                    now.toEpochMilliseconds() / 60_000,
                    deadline,
                    workStartMinutes,
                    workEndMinutes,
                ) {
                    calculateGoalWorkingTime(
                        now = now,
                        deadline = deadline,
                        startMinutesOfDay = workStartMinutes,
                        endMinutesOfDay = workEndMinutes,
                    )
                }
                Text(
                    goalWorkingTimeLabel(working, deadline <= now).uppercase(),
                    color = if (deadline <= now) colors.red else colors.muted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = .7.sp,
                )
            }
        }
        if (deadline != null) {
            Spacer(Modifier.height(12.dp))
            GoalProgressBar(
                now = now,
                start = start ?: now,
                deadline = deadline,
                workStartMinutes = workStartMinutes,
                workEndMinutes = workEndMinutes,
                animationLabel = "goal-progress",
            )
        }
        Spacer(Modifier.height(12.dp))
        GoalTextField(
            value = title,
            semanticLabel = stringResource(Res.string.goal_title_label),
            placeholder = stringResource(Res.string.goal_title_placeholder),
            onValueChange = onTitleChange,
            imeAction = ImeAction.Next,
        )
        Spacer(Modifier.height(9.dp))
        GoalDateRow(
            deadline = deadline,
            start = start,
            now = now,
            onDeadlineChange = onDeadlineChange,
            onDeadlineCommit = onDeadlineCommit,
            onStartChange = onStartChange,
            onStartCommit = onStartCommit,
        )
    }
}

@Composable
internal fun GoalTextField(
    value: String,
    semanticLabel: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onFocusLost: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalDayViewColors.current
    val focusManager = LocalFocusManager.current
    var wasFocused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onNext = { focusManager.moveFocus(FocusDirection.Next) },
            onDone = { focusManager.clearFocus() },
        ),
        textStyle = TextStyle(color = colors.cloud, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        cursorBrush = Brush.verticalGradient(listOf(colors.mint, colors.mint)),
        modifier = modifier.fillMaxWidth()
            .onFocusChanged { focusState ->
                if (wasFocused && !focusState.isFocused) onFocusLost()
                wasFocused = focusState.isFocused
            }
            .semantics { contentDescription = semanticLabel }
            .background(colors.overlay.copy(alpha = .045f), RoundedCornerShape(10.dp))
            .border(
                width = 1.dp,
                color = if (isError) colors.red.copy(alpha = .8f) else colors.overlay.copy(alpha = .07f),
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 12.dp, vertical = 11.dp),
        decorationBox = { innerTextField ->
            Box {
                if (value.isEmpty()) Text(placeholder, color = colors.muted, fontSize = 13.sp)
                innerTextField()
            }
        },
    )
}

private enum class GoalDateTarget { NONE, START, END }

@Composable
private fun GoalProgressBar(
    now: Instant,
    start: Instant,
    deadline: Instant,
    workStartMinutes: Int,
    workEndMinutes: Int,
    animationLabel: String,
) {
    val colors = LocalDayViewColors.current
    val progress = remember(now.toEpochMilliseconds() / 60_000, start, deadline, workStartMinutes, workEndMinutes) {
        calculateGoalProgress(
            now = now,
            start = start,
            deadline = deadline,
            startMinutesOfDay = workStartMinutes,
            endMinutesOfDay = workEndMinutes,
        )
    }
    val animatedProgress by animateFloatAsState(progress, tween(650), label = animationLabel)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(colors.overlay.copy(alpha = .12f)),
        ) {
            Box(
                modifier = Modifier.fillMaxHeight()
                    .fillMaxWidth(animatedProgress)
                    .background(colors.mint, RoundedCornerShape(3.dp)),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(Res.string.goal_progress_percent, (animatedProgress * 100).roundToInt().toString()),
            color = colors.muted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Start → end goal dates as glanceable labels, each opening a date/time picker on tap. */
@Composable
private fun GoalDateRow(
    deadline: Instant?,
    start: Instant?,
    now: Instant,
    onDeadlineChange: (String) -> Unit,
    onDeadlineCommit: () -> Unit,
    onStartChange: (String) -> Unit,
    onStartCommit: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    var picker by remember { mutableStateOf(GoalDateTarget.NONE) }
    val startBeforeDeadlineError = stringResource(Res.string.goal_start_before_deadline)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (deadline != null) {
            GoalDateLabel(
                text = formatGoalDateShort(start ?: now),
                semanticLabel = stringResource(Res.string.goal_start_label),
                isPlaceholder = false,
                onClick = { picker = GoalDateTarget.START },
            )
            Text("→", color = colors.muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 6.dp))
        }
        GoalDateLabel(
            text = deadline?.let(::formatGoalDateShort) ?: stringResource(Res.string.goal_define_deadline),
            semanticLabel = stringResource(Res.string.goal_deadline_label),
            isPlaceholder = deadline == null,
            onClick = { picker = GoalDateTarget.END },
        )
    }
    when (picker) {
        GoalDateTarget.START -> GoalDateTimeDialog(
            initial = start ?: now,
            validate = { candidate ->
                if (deadline != null && candidate >= deadline) {
                    startBeforeDeadlineError
                } else {
                    null
                }
            },
            onConfirm = {
                onStartChange(it)
                onStartCommit()
                picker = GoalDateTarget.NONE
            },
            onDismiss = { picker = GoalDateTarget.NONE },
        )
        GoalDateTarget.END -> GoalDateTimeDialog(
            initial = deadline ?: now,
            validate = { null },
            onConfirm = {
                onDeadlineChange(it)
                onDeadlineCommit()
                picker = GoalDateTarget.NONE
            },
            onDismiss = { picker = GoalDateTarget.NONE },
        )
        GoalDateTarget.NONE -> Unit
    }
}

/** A glanceable goal date that opens a picker dialog on tap. */
@Composable
private fun GoalDateLabel(
    text: String,
    semanticLabel: String,
    isPlaceholder: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    Text(
        text,
        color = if (isPlaceholder) colors.muted else colors.cloud,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClickLabel = semanticLabel, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics { contentDescription = semanticLabel },
    )
}

/**
 * Material3 date + time picker for a goal date. Confirms with the canonical
 * `dd/MM/yyyy HH:mm` string; [validate] returns an error message to keep the dialog
 * open, or null to accept.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalDateTimeDialog(
    initial: Instant,
    validate: (Instant) -> String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    val dateState = rememberDatePickerState(initialSelectedDateMillis = goalPickerDateMillis(initial))
    val timeState = rememberTimePickerState(
        initialHour = goalPickerHour(initial),
        initialMinute = goalPickerMinute(initial),
        is24Hour = true,
    )
    var error by remember { mutableStateOf<String?>(null) }
    val chooseDateError = stringResource(Res.string.goal_choose_date)
    val invalidTimeError = stringResource(Res.string.goal_invalid_time)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val day = dateState.selectedDateMillis
                if (day == null) {
                    error = chooseDateError
                    return@TextButton
                }
                val input = formatGoalPickerInput(day, timeState.hour, timeState.minute)
                val parsed = parseGoalDeadline(input)
                val message = if (parsed == null) invalidTimeError else validate(parsed)
                if (message != null) error = message else onConfirm(input)
            }) { Text(stringResource(Res.string.dialog_ok), color = colors.mint, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.dialog_cancel), color = colors.muted) }
        },
    ) {
        Column(
            Modifier
                .scaledContent(goalPickerScale)
                .verticalScroll(rememberScrollState()),
        ) {
            DatePicker(
                state = dateState,
                showModeToggle = false,
                title = null,
                headline = null,
            )
            TimeInput(
                state = timeState,
                modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 8.dp),
            )
            error?.let {
                Text(
                    it,
                    color = colors.red,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                )
            }
        }
    }
}

/**
 * Renders content at [scale] and reports the scaled size to the parent, so a fixed-size
 * child (the Material3 calendar) shrinks the dialog around it instead of leaving margins.
 */
private fun Modifier.scaledContent(scale: Float): Modifier = if (scale == 1f) {
    this
} else {
    layout { measurable, constraints ->
        val expanded = constraints.copy(
            maxWidth = if (constraints.hasBoundedWidth) (constraints.maxWidth / scale).roundToInt() else constraints.maxWidth,
            maxHeight = if (constraints.hasBoundedHeight) (constraints.maxHeight / scale).roundToInt() else constraints.maxHeight,
        )
        val placeable = measurable.measure(expanded)
        layout((placeable.width * scale).roundToInt(), (placeable.height * scale).roundToInt()) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

@Composable
internal fun TimeButton(
    label: String,
    enabled: Boolean,
    onClickLabel: String,
    valueDescription: String,
    onClick: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    val color = if (enabled) colors.cloud else colors.muted.copy(alpha = .4f)
    Box(
        modifier = Modifier.size(48.dp)
            .background(colors.overlay.copy(alpha = if (enabled) .08f else .025f), CircleShape)
            .semantics { stateDescription = valueDescription }
            .clickable(
                enabled = enabled,
                onClickLabel = onClickLabel,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = 23.sp, fontWeight = FontWeight.Light)
    }
}
