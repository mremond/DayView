package fr.dayview.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.app_wordmark
import fr.dayview.app.generated.resources.busy_generic
import fr.dayview.app.generated.resources.busy_remaining
import fr.dayview.app.generated.resources.busy_time_range
import fr.dayview.app.generated.resources.clean_sessions_today
import fr.dayview.app.generated.resources.clean_streak
import fr.dayview.app.generated.resources.countdown_day_over
import fr.dayview.app.generated.resources.countdown_time_left
import fr.dayview.app.generated.resources.day_available_percent
import fr.dayview.app.generated.resources.detour_time_range
import fr.dayview.app.generated.resources.detours_today
import fr.dayview.app.generated.resources.detours_today_off_window
import fr.dayview.app.generated.resources.dialog_cancel
import fr.dayview.app.generated.resources.dialog_ok
import fr.dayview.app.generated.resources.focus_break_conscious
import fr.dayview.app.generated.resources.focus_break_disconnect
import fr.dayview.app.generated.resources.focus_break_since
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
import fr.dayview.app.generated.resources.history_title
import fr.dayview.app.generated.resources.mini_window_button
import fr.dayview.app.generated.resources.minutes_label
import fr.dayview.app.generated.resources.net_remaining
import fr.dayview.app.generated.resources.scrub_now
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
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private const val CLEAN_SESSION_PIP_CAP = 8

internal data class DayViewScreenActions(
    val openSettings: () -> Unit,
    val onOpenHistory: () -> Unit,
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
    val addDetour: (String, Int) -> Unit,
    val updateDetour: (Int, DetourEpisode) -> Unit,
    val removeDetour: (Int) -> Unit,
    val addDetourEpisode: (DetourEpisode) -> Unit,
    val forgetDetourMotif: (String) -> Unit,
    val addPlannedObligation: (String) -> Unit,
    val removePlannedObligation: (String) -> Unit,
    val completePlannedObligation: (String, String, Int, Int?) -> Unit,
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
    var showDetourCapture by remember { mutableStateOf(false) }
    var showDetourList by remember { mutableStateOf(false) }
    var obligationToComplete by remember { mutableStateOf<String?>(null) }
    var showObligations by remember { mutableStateOf(false) }
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
        // Size the ring from the available width, but never let it take more than ~40% of
        // the height, so on a tall single-column screen the content below stays visible
        // instead of the circle pushing it off the bottom.
        val compactCountdownHeight = (maxWidth - 48.dp)
            .coerceAtMost(maxHeight * 0.40f)
            .coerceIn(240.dp, 480.dp)
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
            Header(actions.openSettings, actions.onOpenHistory, actions.openMiniWindow)
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
                            netTime = state.netTime,
                            focusArcs = state.focusArcsState,
                            focusedToday = state.focusedToday,
                            windowStart = state.dayWindow.first,
                            windowEnd = state.dayWindow.second,
                            detourBodies = state.detourBodiesState,
                            detoursTotal = state.detoursTotalToday,
                            detoursOffWindow = state.detoursOffWindowTotalToday,
                            busyBlockArcs = state.busyBlockArcsState,
                            cleanSessionsToday = state.cleanSessionsToday,
                            streakDays = state.cleanStreakDays,
                            hasGoal = state.goalTitle.isNotBlank() || state.goalDeadline != null,
                            onOpenDetourList = { showDetourList = true },
                        )
                        Spacer(Modifier.height(6.dp))
                        DetourRow(
                            sources = state.detourSourcesState,
                            onOpenList = { showDetourList = true },
                            onCapture = { showDetourCapture = true },
                        )
                        Spacer(Modifier.height(12.dp))
                        PlannedObligationsChip(
                            count = state.plannedObligationsToday.size,
                            cap = MAX_PLANNED_OBLIGATIONS,
                            onOpen = { showObligations = true },
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
                    netTime = state.netTime,
                    focusArcs = state.focusArcsState,
                    focusedToday = state.focusedToday,
                    windowStart = state.dayWindow.first,
                    windowEnd = state.dayWindow.second,
                    detourBodies = state.detourBodiesState,
                    detoursTotal = state.detoursTotalToday,
                    detoursOffWindow = state.detoursOffWindowTotalToday,
                    busyBlockArcs = state.busyBlockArcsState,
                    cleanSessionsToday = state.cleanSessionsToday,
                    streakDays = state.cleanStreakDays,
                    hasGoal = state.goalTitle.isNotBlank() || state.goalDeadline != null,
                    onOpenDetourList = { showDetourList = true },
                )
                Spacer(Modifier.height(6.dp))
                DetourRow(
                    sources = state.detourSourcesState,
                    onOpenList = { showDetourList = true },
                    onCapture = { showDetourCapture = true },
                )
                Spacer(Modifier.height(12.dp))
                PlannedObligationsChip(
                    count = state.plannedObligationsToday.size,
                    cap = MAX_PLANNED_OBLIGATIONS,
                    onOpen = { showObligations = true },
                )
                Spacer(Modifier.height(12.dp))
                CompactTodayContent(
                    state = state,
                    actions = actions,
                    reminders = reminders,
                )
            }
        }
        if (showDetourCapture) {
            DetourCaptureDialog(
                recentMotifs = state.recentDetourMotifs,
                now = state.now,
                onConfirm = { motif, durationMinutes, startMinutesOfDay ->
                    if (startMinutesOfDay == null) {
                        actions.addDetour(motif, durationMinutes)
                    } else {
                        actions.addDetourEpisode(
                            detourEpisodeAt(state.now, startMinutesOfDay, durationMinutes, motif),
                        )
                    }
                    showDetourCapture = false
                },
                onForget = actions.forgetDetourMotif,
                onDismiss = { showDetourCapture = false },
            )
        }
        obligationToComplete?.let { motif ->
            DetourCaptureDialog(
                recentMotifs = state.recentDetourMotifs,
                now = state.now,
                initialMotif = motif,
                onConfirm = { confirmedMotif, durationMinutes, startMinutesOfDay ->
                    actions.completePlannedObligation(motif, confirmedMotif, durationMinutes, startMinutesOfDay)
                    obligationToComplete = null
                },
                onForget = actions.forgetDetourMotif,
                onDismiss = { obligationToComplete = null },
            )
        }
        if (showDetourList) {
            DetourListDialog(
                episodes = state.detoursToday,
                now = state.now,
                windowStart = state.dayWindow.first,
                windowEnd = state.dayWindow.second,
                onUpdate = actions.updateDetour,
                onRemove = actions.removeDetour,
                onAdd = actions.addDetourEpisode,
                onDismiss = { showDetourList = false },
            )
        }
        if (showObligations) {
            PlannedObligationsDialog(
                obligations = state.plannedObligationsToday,
                onAdd = actions.addPlannedObligation,
                onComplete = {
                    obligationToComplete = it
                    showObligations = false
                },
                onRemove = actions.removePlannedObligation,
                onDismiss = { showObligations = false },
            )
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
private fun Header(onOpenSettings: () -> Unit, onOpenHistory: () -> Unit, onOpenMiniWindow: (() -> Unit)?) {
    val colors = LocalDayViewColors.current
    Row(
        modifier = Modifier.fillMaxWidth().widthIn(max = 1040.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(colors.mint, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(Res.string.app_wordmark), color = colors.cloud, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.2.sp)
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(Res.string.history_title),
            color = colors.muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.4.sp,
            modifier = Modifier
                .testTag(DayViewTestTags.HistoryIcon)
                .minimumInteractiveComponentSize()
                .clickable(role = Role.Button, onClick = onOpenHistory)
                .padding(vertical = 10.dp, horizontal = 4.dp),
        )
        Spacer(Modifier.width(6.dp))
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
        onOpenMiniWindow?.let {
            Spacer(Modifier.width(18.dp))
            Box(
                modifier = Modifier
                    .testTag(DayViewTestTags.MiniWindow)
                    .minimumInteractiveComponentSize()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(Res.string.mini_window_button),
                        onClick = it,
                    )
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                MiniWindowGlyph(color = colors.muted)
            }
        }
    }
}

@Composable
internal fun CountdownCircle(
    progress: DayProgress,
    showSeconds: Boolean,
    modifier: Modifier = Modifier,
    netTime: NetTime? = null,
    focusArcs: List<FocusArc> = emptyList(),
    focusedToday: Duration = Duration.ZERO,
    windowStart: Instant = Instant.fromEpochMilliseconds(0L),
    windowEnd: Instant = Instant.fromEpochMilliseconds(0L),
    detourBodies: List<DetourBody> = emptyList(),
    detoursTotal: Duration = Duration.ZERO,
    detoursOffWindow: Duration = Duration.ZERO,
    busyBlockArcs: List<BusyBlockArc> = emptyList(),
    cleanSessionsToday: Int = 0,
    streakDays: Int = 0,
    hasGoal: Boolean = false,
    onOpenDetourList: (() -> Unit)? = null,
) {
    val colors = LocalDayViewColors.current
    val uses24Hour = LocalUses24HourClock.current
    val animatedRemaining by animateFloatAsState(progress.remainingRatio, tween(650), label = "remaining")
    val accent by animateColorAsState(
        when {
            progress.isFinished -> colors.mint
            progress.remainingRatio < .2f -> colors.amber
            else -> colors.mint
        },
        label = "accent",
    )
    var hoveredBusy by remember { mutableStateOf<HoveredBusyArc?>(null) }
    var hoveredDetour by remember { mutableStateOf<HoveredDetourBody?>(null) }
    var scrubAngle by remember { mutableStateOf<Float?>(null) }
    val haptic = LocalHapticFeedback.current

    Box(modifier = modifier.testTag(DayViewTestTags.Countdown), contentAlignment = Alignment.Center) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val circleSize = countdownCircleSize(minOf(maxWidth, maxHeight))
            // Scale the counter type in step with the ring so the numerals keep their
            // proportion: they shrink in the mini and compact windows and grow to fill a
            // large dial on Supernote / a maximized desktop window.
            val counterScale = countdownCounterScale(circleSize)
            val circleModifier = if (busyBlockArcs.isEmpty() && detourBodies.isEmpty()) {
                Modifier.size(circleSize)
            } else {
                Modifier.size(circleSize).pointerInput(busyBlockArcs, detourBodies) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val position = event.changes.firstOrNull()?.position
                            if (event.type == PointerEventType.Exit || position == null) {
                                hoveredBusy = null
                                hoveredDetour = null
                            } else if (
                                (
                                    event.type == PointerEventType.Move ||
                                        event.type == PointerEventType.Enter
                                    ) &&
                                event.changes.firstOrNull()?.type == PointerType.Mouse
                            ) {
                                val body = hitTestDetourBody(position.x, position.y, size.width, size.height, detourBodies)
                                hoveredDetour = body?.let { HoveredDetourBody(it, position) }
                                hoveredBusy = if (body != null) {
                                    null
                                } else {
                                    hitTestBusyArc(position, size.width, size.height, busyBlockArcs)
                                        ?.let { HoveredBusyArc(it, position) }
                                }
                            } else if (event.type == PointerEventType.Press) {
                                if (event.changes.firstOrNull()?.type == PointerType.Mouse &&
                                    hitTestDetourBody(position.x, position.y, size.width, size.height, detourBodies) != null
                                ) {
                                    onOpenDetourList?.invoke()
                                }
                            }
                        }
                    }
                }
            }
            val scrubModifier = circleModifier.pointerInput(busyBlockArcs, detourBodies, focusArcs) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.type != PointerType.Touch) return@awaitEachGesture
                    val pressed = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    fun angleOf(pos: Offset): Float {
                        val dx = pos.x - size.width / 2f
                        val dy = pos.y - size.height / 2f
                        val raw = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                        return normalizeRingAngle(raw)
                    }
                    try {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        scrubAngle = angleOf(pressed.position)
                        pressed.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            scrubAngle = angleOf(change.position)
                            change.consume()
                        }
                    } finally {
                        scrubAngle = null
                    }
                }
            }
            Box(scrubModifier, contentAlignment = Alignment.Center) {
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

                    if (hasGoal) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(colors.amber.copy(alpha = .10f), Color.Transparent),
                                center = center,
                                radius = size.minDimension * .30f,
                            ),
                            radius = size.minDimension * .30f,
                            center = center,
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
                    } else if (progress.isFinished) {
                        // Day complete: the ring comes to rest as a full, vivid mint circle
                        // (uniform colour — no leading edge to justify a sweep gradient), with a
                        // small resting marker parked at the top where the day began and ended.
                        drawArc(
                            color = accent,
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(strokeWidth, cap = StrokeCap.Round),
                        )
                        val restCenter = Offset(size.width / 2f, inset)
                        drawCircle(
                            color = accent.copy(alpha = .22f),
                            radius = strokeWidth * .6f,
                            center = restCenter,
                        )
                        drawCircle(
                            color = accent,
                            radius = strokeWidth * .34f,
                            center = restCenter,
                        )
                    }

                    // Calendar busy is its own cool-toned layer on a concentric lane just inside
                    // the ring, over the dark interior — so cool colours read at full contrast and
                    // never fight the green sweep, while hue means "reserved". A wide low-alpha
                    // pass gives the glow, a narrower bright pass the core; round caps let short
                    // events settle in as soft pills.
                    val busyInset = inset + strokeWidth * .95f
                    val busyLaneSize = Size(size.width - busyInset * 2, size.height - busyInset * 2)
                    val residualAlpha = if (progress.isFinished) .4f else 1f
                    busyBlockArcs.forEach { arc ->
                        val col = colors.busy[arc.colorIndex % colors.busy.size]
                        drawArc(
                            color = col.copy(alpha = .16f * residualAlpha),
                            startAngle = arc.startAngleDegrees,
                            sweepAngle = arc.sweepDegrees,
                            useCenter = false,
                            topLeft = Offset(busyInset, busyInset),
                            size = busyLaneSize,
                            style = Stroke(strokeWidth * .7f, cap = StrokeCap.Round),
                        )
                        drawArc(
                            color = col.copy(alpha = .92f * residualAlpha),
                            startAngle = arc.startAngleDegrees,
                            sweepAngle = arc.sweepDegrees,
                            useCenter = false,
                            topLeft = Offset(busyInset, busyInset),
                            size = busyLaneSize,
                            style = Stroke(strokeWidth * .42f, cap = StrokeCap.Round),
                        )
                    }

                    detourBodies.forEach { body ->
                        val angleRadians = Math.toRadians(body.angleDegrees.toDouble())
                        // Offset each body off the orbit by its weight: light detours drift just
                        // outside the ring, heavy ones sink inside, mid-size ones ride the line.
                        val bodyRadius = arcSize.width / 2f + strokeWidth * (.6f - 1.2f * body.sizeFraction)
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val bodyCenter = center + Offset(
                            x = (kotlin.math.cos(angleRadians) * bodyRadius).toFloat(),
                            y = (kotlin.math.sin(angleRadians) * bodyRadius).toFloat(),
                        )
                        val color = colors.detours[body.colorIndex % colors.detours.size]
                        // Keep a visible floor size so short detours still read, especially when
                        // they land next to the moment marker right after being added.
                        val radius = strokeWidth * (.42f + .32f * body.sizeFraction)
                        drawCircle(color = color.copy(alpha = .28f * residualAlpha), radius = radius * 1.5f, center = bodyCenter)
                        drawCircle(color = color.copy(alpha = residualAlpha), radius = radius, center = bodyCenter)
                        drawCircle(
                            color = Color.White.copy(alpha = .5f * residualAlpha),
                            radius = radius * .28f,
                            center = bodyCenter - Offset(radius * .3f, radius * .3f),
                        )
                    }

                    scrubAngle?.let { angle ->
                        val angleRadians = Math.toRadians(angle.toDouble())
                        val arcRadius = arcSize.width / 2f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val markCenter = center + Offset(
                            (kotlin.math.cos(angleRadians) * arcRadius).toFloat(),
                            (kotlin.math.sin(angleRadians) * arcRadius).toFloat(),
                        )
                        drawCircle(color = colors.cloud.copy(alpha = .22f), radius = strokeWidth * 1.15f, center = markCenter)
                        drawCircle(color = colors.cloud, radius = strokeWidth * .55f, center = markCenter)
                    }
                }

                val prefFontScale = LocalPreferenceFontScale.current
                val counterDensity = LocalDensity.current
                CompositionLocalProvider(
                    // The ring grows to fill space and the numerals track it via counterScale;
                    // keep them ring-proportional rather than following the accessibility text
                    // slider, so they never overflow the dial. Dividing the preference
                    // multiplier back out preserves the OS font setting while dropping the slider.
                    LocalDensity provides Density(counterDensity.density, counterDensity.fontScale / prefFontScale),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (progress.isFinished) stringResource(Res.string.countdown_day_over) else stringResource(Res.string.countdown_time_left),
                            color = if (progress.isFinished) colors.mint else colors.muted,
                            fontSize = (11 * counterScale).sp,
                            lineHeight = (15 * counterScale).sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (1.8f * counterScale).sp,
                            textAlign = TextAlign.Center,
                        )
                        if (!progress.isFinished) {
                            Spacer(Modifier.height(8.dp * counterScale))
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(progress.remainingHours.toString().padStart(2, '0'), color = colors.cloud, fontSize = (52 * counterScale).sp, fontWeight = FontWeight.Light)
                                Text("h", color = colors.muted, fontSize = (19 * counterScale).sp, modifier = Modifier.padding(bottom = 9.dp * counterScale, start = 3.dp * counterScale, end = 7.dp * counterScale))
                                Text(progress.remainingMinutes.toString().padStart(2, '0'), color = colors.cloud, fontSize = (52 * counterScale).sp, fontWeight = FontWeight.Light)
                            }
                            if (showSeconds) {
                                Text(
                                    stringResource(Res.string.seconds_remaining, progress.remainingSeconds.toString().padStart(2, '0')),
                                    color = colors.muted,
                                    fontSize = (12 * counterScale).sp,
                                    letterSpacing = (.8f * counterScale).sp,
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
                            if (detoursTotal > Duration.ZERO) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    if (detoursOffWindow > Duration.ZERO) {
                                        stringResource(
                                            Res.string.detours_today_off_window,
                                            formatDurationHm(detoursTotal),
                                            formatDurationHm(detoursOffWindow),
                                        )
                                    } else {
                                        stringResource(Res.string.detours_today, formatDurationHm(detoursTotal))
                                    },
                                    color = colors.amber,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = .5.sp,
                                )
                            }
                        } else if (focusedToday > Duration.ZERO) {
                            Spacer(Modifier.height(8.dp * counterScale))
                            Text(
                                stringResource(Res.string.focused_today, formatDurationHm(focusedToday)),
                                color = colors.mint,
                                fontSize = (13 * counterScale).sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = (.5f * counterScale).sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.testTag(DayViewTestTags.FocusRecap),
                            )
                        }
                        if (cleanSessionsToday > 0 || streakDays > 0) {
                            Spacer(Modifier.height(6.dp))
                            val countLabel = if (cleanSessionsToday > 0) {
                                stringResource(Res.string.clean_sessions_today, cleanSessionsToday)
                            } else {
                                null
                            }
                            val streakLabel = if (streakDays > 0) {
                                stringResource(Res.string.clean_streak, streakDays)
                            } else {
                                null
                            }
                            val label = listOfNotNull(countLabel, streakLabel).joinToString(" · ")
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.testTag(DayViewTestTags.CleanSessions),
                            ) {
                                if (cleanSessionsToday > 0) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        repeat(cleanSessionsToday.coerceAtMost(CLEAN_SESSION_PIP_CAP)) {
                                            Box(
                                                Modifier
                                                    .size(6.dp)
                                                    .background(colors.mint, CircleShape),
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                }
                                Text(
                                    label,
                                    color = colors.mint,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = .5.sp,
                                )
                            }
                        }
                    }
                }

                hoveredBusy?.let { hovered ->
                    val arc = hovered.arc
                    val startLabel = formatClockHm(arc.start, use24Hour = uses24Hour)
                    val endLabel = formatClockHm(arc.end, use24Hour = uses24Hour)
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
                            if (arc.calendarName.isNotBlank()) {
                                Text(
                                    arc.calendarName,
                                    color = colors.busy[arc.colorIndex % colors.busy.size],
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = .5.sp,
                                )
                            }
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

                hoveredDetour?.let { hovered ->
                    val body = hovered.body
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
                            Text(body.motif, color = colors.cloud, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                stringResource(
                                    Res.string.detour_time_range,
                                    formatClockHm(body.start, use24Hour = uses24Hour),
                                    formatClockHm(body.end, use24Hour = uses24Hour),
                                    formatDurationHm(body.end - body.start),
                                ),
                                color = colors.muted,
                                fontSize = 11.sp,
                                letterSpacing = .5.sp,
                            )
                        }
                    }
                }

                scrubAngle?.let { angle ->
                    val momentAngle = if (progress.hasStarted && !progress.isFinished) {
                        currentMomentAngleDegrees(animatedRemaining)
                    } else {
                        null
                    }
                    val readout = ringReadoutAt(
                        angle,
                        windowStart,
                        windowEnd,
                        busyBlockArcs,
                        detourBodies,
                        focusArcs,
                        momentAngle,
                    )
                    RingScrubReadout(
                        readout = readout,
                        uses24Hour = uses24Hour,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                    )
                }
            }
        }
    }
}

/**
 * Fixed readout for the touch ring scrub: the time of day under the mark, a "now" pill when
 * the mark sits on the current moment, then any calendar-busy / detour / focus layer present
 * at that angle, each in its layer colour. Content is computed by [ringReadoutAt].
 */
@Composable
private fun RingScrubReadout(
    readout: RingReadout,
    uses24Hour: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDayViewColors.current
    Box(
        modifier = modifier
            .background(colors.panel, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatClockHm(readout.time, use24Hour = uses24Hour),
                    color = colors.cloud,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (readout.isNow) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(Res.string.scrub_now).uppercase(),
                        color = colors.amber,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                }
            }
            readout.busy?.let { arc ->
                val titles = arc.titles.filter { it.isNotBlank() }
                Text(
                    if (titles.isEmpty()) stringResource(Res.string.busy_generic) else titles.joinToString(" · "),
                    color = colors.busy[arc.colorIndex % colors.busy.size],
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            readout.detour?.let { body ->
                Text(
                    stringResource(
                        Res.string.detour_time_range,
                        formatClockHm(body.start, use24Hour = uses24Hour),
                        formatClockHm(body.end, use24Hour = uses24Hour),
                        formatDurationHm(body.end - body.start),
                    ),
                    color = colors.detours[body.colorIndex % colors.detours.size],
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (readout.focus) {
                Text(
                    stringResource(Res.string.focus_section),
                    color = colors.mint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}

private data class HoveredBusyArc(val arc: BusyBlockArc, val position: Offset)
private data class HoveredDetourBody(val body: DetourBody, val position: Offset)

/** Renvoie l'arc occupé sous le pointeur, ou null si le pointeur n'est pas sur l'anneau. */
private fun hitTestBusyArc(
    position: Offset,
    width: Int,
    height: Int,
    busyBlockArcs: List<BusyBlockArc>,
): BusyBlockArc? {
    val cx = width / 2f
    val cy = height / 2f
    val dx = position.x - cx
    val dy = position.y - cy
    // The busy band rides an inner lane, so the radius window reaches further in than the
    // detour band; the nearest arc within a small angular tolerance wins, which gives very
    // short events (a thin arc) a hoverable margin instead of a pixel-wide target.
    val radiusFraction = hypot(dx, dy) / (minOf(width, height) / 2f)
    if (radiusFraction !in 0.60f..1.02f) return null
    val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    return busyBlockArcs
        .minByOrNull { angularDistanceToArc(it.startAngleDegrees, it.sweepDegrees, angle) }
        ?.takeIf { angularDistanceToArc(it.startAngleDegrees, it.sweepDegrees, angle) <= 5f }
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
        val heroSlot = when {
            !progress.hasStarted -> HeroQuoteSlot.NOT_STARTED
            progress.isFinished -> HeroQuoteSlot.FINISHED
            progress.remainingRatio < .2f -> HeroQuoteSlot.ENDING
            else -> HeroQuoteSlot.ONGOING
        }
        val heroQuotes = stringArrayResource(
            when (heroSlot) {
                HeroQuoteSlot.NOT_STARTED -> Res.array.today_hero_not_started
                HeroQuoteSlot.FINISHED -> Res.array.today_hero_finished
                HeroQuoteSlot.ENDING -> Res.array.today_hero_ending
                HeroQuoteSlot.ONGOING -> Res.array.today_hero_ongoing
            },
        )
        Text(
            text = heroQuotes[heroQuoteIndex(heroQuotes.size, HeroQuoteSelection.seed(heroSlot))],
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
                FocusActionButton(
                    stringResource(Res.string.focus_stop),
                    colors.red,
                    modifier = Modifier.testTag(DayViewTestTags.FocusStop),
                    onClick = onStop,
                )
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatBreakClock(progress), color = colors.cloud, fontSize = 34.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.weight(1f))
                FocusRelaunchRoundButton(onStart, Modifier.testTag(DayViewTestTags.FocusRelaunch))
                Spacer(Modifier.width(8.dp))
                FocusStopRoundButton(onStop, Modifier.testTag(DayViewTestTags.FocusStop))
            }
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
            Spacer(Modifier.height(14.dp))
            FocusClosureSection(onClose)
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
        modifier = Modifier.fillMaxWidth().testTag(DayViewTestTags.FocusStart),
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
    visualTransformation: VisualTransformation = VisualTransformation.None,
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
        visualTransformation = visualTransformation,
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
        is24Hour = LocalUses24HourClock.current,
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
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    val color = if (enabled) colors.cloud else colors.muted.copy(alpha = .4f)
    Box(
        modifier = modifier.size(48.dp)
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
