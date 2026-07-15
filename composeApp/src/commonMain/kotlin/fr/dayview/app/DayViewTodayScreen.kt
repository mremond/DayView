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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
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
import fr.dayview.app.generated.resources.countdown_a11y_finished
import fr.dayview.app.generated.resources.countdown_a11y_remaining
import fr.dayview.app.generated.resources.countdown_day_over
import fr.dayview.app.generated.resources.countdown_time_left
import fr.dayview.app.generated.resources.day_available_percent
import fr.dayview.app.generated.resources.detour_section
import fr.dayview.app.generated.resources.detours_today
import fr.dayview.app.generated.resources.detours_today_off_window
import fr.dayview.app.generated.resources.dialog_cancel
import fr.dayview.app.generated.resources.dialog_ok
import fr.dayview.app.generated.resources.engaged_today
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
import fr.dayview.app.generated.resources.notice_calendar_error
import fr.dayview.app.generated.resources.notice_calendar_permission
import fr.dayview.app.generated.resources.notice_review_action
import fr.dayview.app.generated.resources.notice_sync_failed
import fr.dayview.app.generated.resources.notice_sync_key_error
import fr.dayview.app.generated.resources.open_detour_status
import fr.dayview.app.generated.resources.scrub_now
import fr.dayview.app.generated.resources.seconds_remaining
import fr.dayview.app.generated.resources.settings_title
import fr.dayview.app.generated.resources.today_hero_ending
import fr.dayview.app.generated.resources.today_hero_ending_sources
import fr.dayview.app.generated.resources.today_hero_finished
import fr.dayview.app.generated.resources.today_hero_finished_sources
import fr.dayview.app.generated.resources.today_hero_not_started
import fr.dayview.app.generated.resources.today_hero_not_started_sources
import fr.dayview.app.generated.resources.today_hero_ongoing
import fr.dayview.app.generated.resources.today_hero_ongoing_sources
import fr.dayview.app.generated.resources.today_status_ending
import fr.dayview.app.generated.resources.today_status_finished
import fr.dayview.app.generated.resources.today_status_not_started
import fr.dayview.app.generated.resources.today_status_ongoing
import fr.dayview.app.sync.SyncStatus
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
    val addDetour: (String, Int, String) -> Unit,
    val updateDetour: (Int, DetourEpisode) -> Unit,
    val removeDetour: (Int) -> Unit,
    val addDetourEpisode: (DetourEpisode) -> Unit,
    val startOpenDetour: (String, String) -> Unit,
    val stopOpenDetour: () -> Unit,
    val forgetDetourCategory: (String) -> Unit,
    val addPlannedObligation: (String) -> Unit,
    val removePlannedObligation: (String) -> Unit,
    val completePlannedObligation: (String) -> Unit,
    val editPlannedObligation: (String, String) -> Unit = { _, _ -> },
    val openNetTimeSettings: () -> Unit = {},
    val openSyncSettings: () -> Unit = {},
)

internal data class FocusReminderUiState(
    val showDriftReminder: Boolean,
    val dismissDriftReminder: () -> Unit,
    val showResumeRitual: Boolean,
    val dismissResumeRitual: () -> Unit,
)

internal data class OpenDetourPanelState(
    val elapsed: Duration,
    val category: String,
    val description: String,
)

internal val DayViewUiState.openDetourPanelState: OpenDetourPanelState?
    get() = openDetourStart?.let {
        OpenDetourPanelState(openDetourElapsed, openDetourCategory, openDetourDescription)
    }

@Composable
internal fun DayViewScreen(
    state: DayViewUiState,
    actions: DayViewScreenActions,
    reminders: FocusReminderUiState,
    syncStatus: SyncStatus = SyncStatus.Idle,
) {
    val colors = LocalDayViewColors.current
    val progress = state.dayProgress
    val pomodoro = state.pomodoroProgress
    var showDetourCapture by remember { mutableStateOf(false) }
    var showDetourList by remember { mutableStateOf(false) }
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
        val wide = useWideTodayLayout(
            widthDp = maxWidth.value,
            heightDp = maxHeight.value,
            fontScale = LocalPreferenceFontScale.current,
        )
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
            TodayNotices(
                state = state,
                syncStatus = syncStatus,
                onOpenNetTimeSettings = actions.openNetTimeSettings,
                onOpenSyncSettings = actions.openSyncSettings,
            )
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
                            sessionFocusedToday = state.sessionFocusedToday,
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
                        TodayQuickActions(
                            activeObligationCount = state.plannedObligationsToday.size,
                            onAddDetour = { showDetourCapture = true },
                            onOpenObligations = { showObligations = true },
                        )
                        Spacer(Modifier.height(12.dp))
                        LongTermGoalPanel(
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
                        openDetour = state.openDetourPanelState,
                        onStopOpenDetour = actions.stopOpenDetour,
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
                    sessionFocusedToday = state.sessionFocusedToday,
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
                TodayQuickActions(
                    activeObligationCount = state.plannedObligationsToday.size,
                    onAddDetour = { showDetourCapture = true },
                    onOpenObligations = { showObligations = true },
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
                recentCategories = state.recentDetourCategories,
                now = state.now,
                onConfirm = { category, description, durationMinutes, startMinutesOfDay ->
                    if (startMinutesOfDay == null) {
                        actions.addDetour(category, durationMinutes, description)
                    } else {
                        actions.addDetourEpisode(
                            detourEpisodeAt(state.now, startMinutesOfDay, durationMinutes, category, description),
                        )
                    }
                    showDetourCapture = false
                },
                onForget = actions.forgetDetourCategory,
                onDismiss = { showDetourCapture = false },
                onStart = if (state.openDetourRunning) {
                    null
                } else {
                    { category, description ->
                        actions.startOpenDetour(category, description)
                        showDetourCapture = false
                    }
                },
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
                completedObligations = state.plannedObligationsCompletedToday,
                onAdd = actions.addPlannedObligation,
                onComplete = actions.completePlannedObligation,
                onRemove = actions.removePlannedObligation,
                onEdit = actions.editPlannedObligation,
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

        val openDetour = state.openDetourPanelState
        if (openDetour != null) {
            OpenDetourPanel(openDetour, actions.stopOpenDetour)
        } else if (pomodoro.status == PomodoroStatus.IDLE) {
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
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
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
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
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
            modifier = Modifier.fillMaxWidth().testTag(DayViewTestTags.FocusEntry),
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

private data class NoticeSpec(val message: String, val accent: Color, val tag: String, val onClick: () -> Unit)

/**
 * Surfaces background failures the user would otherwise never see from Today: a calendar
 * that Net Time can't read (permission off or a provider error) and a failing sync. Each
 * notice is a tappable banner that jumps straight to the setting that resolves it. Renders
 * nothing when everything is healthy.
 */
@Composable
private fun TodayNotices(
    state: DayViewUiState,
    syncStatus: SyncStatus,
    onOpenNetTimeSettings: () -> Unit,
    onOpenSyncSettings: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    val notices = buildList {
        if (state.netTimeSettings.enabled && !state.netCalendarPermission) {
            add(
                NoticeSpec(
                    stringResource(Res.string.notice_calendar_permission),
                    colors.amber,
                    DayViewTestTags.CalendarNotice,
                    onOpenNetTimeSettings,
                ),
            )
        } else if (state.netTimeSettings.enabled && state.netCalendarError) {
            add(
                NoticeSpec(
                    stringResource(Res.string.notice_calendar_error),
                    colors.amber,
                    DayViewTestTags.CalendarNotice,
                    onOpenNetTimeSettings,
                ),
            )
        }
        when (syncStatus) {
            SyncStatus.Failed ->
                add(
                    NoticeSpec(
                        stringResource(Res.string.notice_sync_failed),
                        colors.red,
                        DayViewTestTags.SyncNotice,
                        onOpenSyncSettings,
                    ),
                )
            SyncStatus.KeyError ->
                add(
                    NoticeSpec(
                        stringResource(Res.string.notice_sync_key_error),
                        colors.red,
                        DayViewTestTags.SyncNotice,
                        onOpenSyncSettings,
                    ),
                )
            else -> Unit
        }
    }
    if (notices.isEmpty()) return
    val reviewLabel = stringResource(Res.string.notice_review_action)
    Spacer(Modifier.height(14.dp))
    Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 1040.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        notices.forEach { notice ->
            Row(
                modifier = Modifier.fillMaxWidth()
                    .testTag(notice.tag)
                    .clip(RoundedCornerShape(12.dp))
                    .background(notice.accent.copy(alpha = .12f))
                    .clickable(role = Role.Button, onClickLabel = reviewLabel, onClick = notice.onClick)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(8.dp).background(notice.accent, CircleShape))
                Text(
                    notice.message,
                    color = colors.cloud,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .testTag(DayViewTestTags.SettingsIcon)
                .minimumInteractiveComponentSize()
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

/**
 * A single spoken summary of the countdown ring for assistive technology. The dial is a
 * Canvas with no intrinsic text, so this collapses the state the sighted user reads from
 * the arc and centre readout — time left, share of the day, net time, focus and detours —
 * into one description.
 */
@Composable
private fun ringContentDescription(
    progress: DayProgress,
    netTime: NetTime?,
    focusedToday: Duration,
    detoursTotal: Duration,
): String {
    if (!progress.hasStarted) return stringResource(Res.string.today_status_not_started)
    if (progress.isFinished) {
        val finished = stringResource(Res.string.countdown_a11y_finished)
        return if (focusedToday > Duration.ZERO) {
            "$finished ${stringResource(Res.string.focused_today, formatDurationHm(focusedToday))}"
        } else {
            finished
        }
    }
    val parts = mutableListOf(
        stringResource(
            Res.string.countdown_a11y_remaining,
            formatDurationHm(progress.remaining),
            progress.percentageRemaining,
        ),
    )
    if (netTime != null && netTime.busyRemaining > Duration.ZERO) {
        parts += stringResource(Res.string.net_remaining, formatDurationHm(netTime.netRemaining))
    }
    if (focusedToday > Duration.ZERO) {
        parts += stringResource(Res.string.focused_today, formatDurationHm(focusedToday))
    }
    if (detoursTotal > Duration.ZERO) {
        parts += stringResource(Res.string.detours_today, formatDurationHm(detoursTotal))
    }
    return parts.joinToString(" ")
}

@Composable
internal fun CountdownCircle(
    progress: DayProgress,
    showSeconds: Boolean,
    modifier: Modifier = Modifier,
    netTime: NetTime? = null,
    focusArcs: List<FocusArc> = emptyList(),
    focusedToday: Duration = Duration.ZERO,
    sessionFocusedToday: Duration = Duration.ZERO,
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
                    fun angleOf(pos: Offset): Float {
                        val dx = pos.x - size.width / 2f
                        val dy = pos.y - size.height / 2f
                        val raw = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                        return normalizeRingAngle(raw)
                    }
                    // A quick release before the long-press timeout is a tap; the timeout firing
                    // (PointerEventTimeoutCancellationException) is a long press → ring scrub.
                    var longPress = false
                    val up = try {
                        withTimeout(viewConfiguration.longPressTimeoutMillis) {
                            waitForUpOrCancellation()
                        }
                    } catch (_: PointerEventTimeoutCancellationException) {
                        longPress = true
                        null
                    }
                    when {
                        longPress -> {
                            try {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                scrubAngle = angleOf(down.position)
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
                        up != null -> {
                            // Tap: reveal / switch / dismiss the busy-label tooltip.
                            val tapped = hitTestBusyArc(up.position, size.width, size.height, busyBlockArcs)
                            hoveredBusy = nextHoveredBusyOnTap(hoveredBusy, tapped, up.position)
                            up.consume()
                        }
                        // else: waitForUpOrCancellation returned null (gesture cancelled) → do nothing.
                    }
                }
            }
            // The ring is drawn on a Canvas that carries no text of its own, so screen
            // readers would otherwise skip the primary visualization entirely. Fold the
            // remaining time, net time, focus and detours into one spoken summary.
            val ringDescription = ringContentDescription(progress, netTime, focusedToday, detoursTotal)
            Box(scrubModifier, contentAlignment = Alignment.Center) {
                Canvas(
                    Modifier.fillMaxSize().semantics { contentDescription = ringDescription },
                ) {
                    val strokeWidth = size.minDimension * .055f
                    // Reserve a concentric lane just outside the ring for detour arcs (mirror of
                    // the calendar-busy lane inside it); the ring itself shrinks by that width.
                    val detourLaneOutset = strokeWidth * .95f
                    val inset = strokeWidth / 2 + 4.dp.toPx() + detourLaneOutset
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
                        // Day complete: the ring comes to rest as a calm, neutral circle rather
                        // than a vivid mint one — the same subdued treatment a finished day gets
                        // in the history grid (MiniRing draws no sweep once nothing remains, so it
                        // rests on the plain track). This keeps the closed dial from reading as
                        // heavy green, with a small resting marker parked at the top where the day
                        // began and ended.
                        drawArc(
                            color = colors.overlay.copy(alpha = .16f),
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(strokeWidth, cap = StrokeCap.Round),
                        )
                        val restCenter = Offset(size.width / 2f, inset)
                        drawCircle(
                            color = colors.overlay.copy(alpha = .12f),
                            radius = strokeWidth * .6f,
                            center = restCenter,
                        )
                        drawCircle(
                            color = colors.muted,
                            radius = strokeWidth * .34f,
                            center = restCenter,
                        )
                    }

                    // Calendar busy is its own cool-toned layer on a concentric lane just inside
                    // the ring, over the dark interior — so cool colours read at full contrast and
                    // never fight the green sweep, while hue means "reserved". A wide low-alpha
                    // pass gives the glow, a narrower bright pass the core; round caps let short
                    // events settle in as soft pills.
                    // The ring rests beneath these markers, so a completed day keeps its events
                    // and distractions at full live-view colour — the green never washes them out.
                    val busyInset = inset + strokeWidth * .95f
                    val busyLaneSize = Size(size.width - busyInset * 2, size.height - busyInset * 2)
                    busyBlockArcs.forEach { arc ->
                        val col = colors.busy[arc.colorIndex % colors.busy.size]
                        drawArc(
                            color = col.copy(alpha = .16f),
                            startAngle = arc.startAngleDegrees,
                            sweepAngle = arc.sweepDegrees,
                            useCenter = false,
                            topLeft = Offset(busyInset, busyInset),
                            size = busyLaneSize,
                            style = Stroke(strokeWidth * .7f, cap = StrokeCap.Round),
                        )
                        drawArc(
                            color = col.copy(alpha = .92f),
                            startAngle = arc.startAngleDegrees,
                            sweepAngle = arc.sweepDegrees,
                            useCenter = false,
                            topLeft = Offset(busyInset, busyInset),
                            size = busyLaneSize,
                            style = Stroke(strokeWidth * .42f, cap = StrokeCap.Round),
                        )
                    }

                    // Detours ride a concentric lane just OUTSIDE the ring — the mirror of the
                    // calendar-busy lane inside it. Each episode is an arc across its real
                    // duration (floored to a visible minimum), coloured per category. A wide
                    // low-alpha pass gives the glow, a narrower bright pass the core.
                    val detourInset = inset - detourLaneOutset
                    val detourLaneSize = Size(size.width - detourInset * 2, size.height - detourInset * 2)
                    detourBodies.forEach { body ->
                        val col = colors.detours[body.colorIndex % colors.detours.size]
                        drawArc(
                            color = col.copy(alpha = .16f),
                            startAngle = body.startAngleDegrees,
                            sweepAngle = body.sweepDegrees,
                            useCenter = false,
                            topLeft = Offset(detourInset, detourInset),
                            size = detourLaneSize,
                            style = Stroke(strokeWidth * .7f, cap = StrokeCap.Round),
                        )
                        drawArc(
                            color = col.copy(alpha = .92f),
                            startAngle = body.startAngleDegrees,
                            sweepAngle = body.sweepDegrees,
                            useCenter = false,
                            topLeft = Offset(detourInset, detourInset),
                            size = detourLaneSize,
                            style = Stroke(strokeWidth * .42f, cap = StrokeCap.Round),
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
                    val hasNetRow = netTime != null && netTime.busyRemaining > Duration.ZERO
                    val interior = countdownInterior(
                        circleSize = circleSize,
                        counterScale = counterScale,
                        showSeconds = showSeconds,
                        hasNet = hasNetRow,
                        hasBusy = hasNetRow,
                        hasFocus = focusedToday > Duration.ZERO,
                        hasEngaged = sessionFocusedToday > Duration.ZERO,
                        hasDetours = detoursTotal > Duration.ZERO,
                        hasAccolades = cleanSessionsToday > 0 || streakDays > 0,
                    )
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
                            if (interior.showNet && netTime != null) {
                                Spacer(Modifier.height(6.dp * counterScale))
                                Text(
                                    if (interior.netCompact) {
                                        formatDurationHm(netTime.netRemaining)
                                    } else {
                                        stringResource(Res.string.net_remaining, formatDurationHm(netTime.netRemaining))
                                    },
                                    color = colors.mint,
                                    fontSize = (14 * counterScale).sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = (.5f * counterScale).sp,
                                    modifier = Modifier.testTag(DayViewTestTags.NetRemaining),
                                )
                                if (interior.showBusy) {
                                    Text(
                                        stringResource(Res.string.busy_remaining, formatDurationHm(netTime.busyRemaining)),
                                        color = colors.muted,
                                        fontSize = (11 * counterScale).sp,
                                        letterSpacing = (.5f * counterScale).sp,
                                    )
                                }
                            }
                            if (interior.showFocus) {
                                if (focusedToday > Duration.ZERO) {
                                    Spacer(Modifier.height(6.dp * counterScale))
                                    Text(
                                        stringResource(Res.string.focused_today, formatDurationHm(focusedToday)),
                                        color = colors.mint,
                                        fontSize = (13 * counterScale).sp,
                                        fontWeight = FontWeight.Medium,
                                        letterSpacing = (.5f * counterScale).sp,
                                    )
                                }
                                if (sessionFocusedToday > Duration.ZERO) {
                                    Spacer(Modifier.height(6.dp * counterScale))
                                    Text(
                                        stringResource(Res.string.engaged_today, formatDurationHm(sessionFocusedToday)),
                                        color = colors.mint,
                                        fontSize = (13 * counterScale).sp,
                                        fontWeight = FontWeight.Medium,
                                        letterSpacing = (.5f * counterScale).sp,
                                    )
                                }
                            }
                            if (interior.showDetours) {
                                Spacer(Modifier.height(6.dp * counterScale))
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
                                    fontSize = (13 * counterScale).sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = (.5f * counterScale).sp,
                                    modifier = Modifier.testTag(DayViewTestTags.Detours),
                                )
                            }
                        } else if (focusedToday > Duration.ZERO || sessionFocusedToday > Duration.ZERO) {
                            if (focusedToday > Duration.ZERO) {
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
                            if (sessionFocusedToday > Duration.ZERO) {
                                Spacer(Modifier.height(if (focusedToday > Duration.ZERO) 6.dp * counterScale else 8.dp * counterScale))
                                Text(
                                    stringResource(Res.string.engaged_today, formatDurationHm(sessionFocusedToday)),
                                    color = colors.mint,
                                    fontSize = (13 * counterScale).sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = (.5f * counterScale).sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.testTag(DayViewTestTags.EngagedRecap),
                                )
                            }
                        }
                        if (interior.showAccolades && (cleanSessionsToday > 0 || streakDays > 0)) {
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
                    HoverTooltip(position = hovered.position, colors = colors) {
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

                hoveredDetour?.let { hovered ->
                    HoverTooltip(position = hovered.position, colors = colors) {
                        DetourReadoutDetails(hovered.body, categoryColor = colors.cloud)
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
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
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
            .widthIn(max = 280.dp)
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
                DetourReadoutDetails(
                    body,
                    categoryColor = colors.detours[body.colorIndex % colors.detours.size],
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

/**
 * The shared body of a detour detail pop-up: category (the "nature", colored by the
 * caller to match its surface), the optional description on a single ellipsized line,
 * and how long the detour lasted. The absolute start/end clock time is intentionally
 * omitted — it is only worth showing for an otherwise-empty tooltip, and a detour always
 * has a category. Flows into the caller's Column so it inherits its alignment.
 */
@Composable
internal fun ColumnScope.DetourReadoutDetails(body: DetourBody, categoryColor: Color) {
    val colors = LocalDayViewColors.current
    Text(body.category, color = categoryColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    if (body.description.isNotEmpty()) {
        Text(
            body.description,
            color = colors.muted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Text(formatDurationHm(body.end - body.start), color = colors.muted, fontSize = 11.sp)
}

internal data class HoveredBusyArc(val arc: BusyBlockArc, val position: Offset)

/**
 * Next [HoveredBusyArc] state after a touch tap on the ring. Tapping another zone switches to
 * it, tapping the shown zone or an empty part of the ring closes the tooltip. Kept pure so the
 * tap/close rules are unit-testable without driving a gesture.
 */
internal fun nextHoveredBusyOnTap(
    current: HoveredBusyArc?,
    tapped: BusyBlockArc?,
    position: Offset,
): HoveredBusyArc? = when {
    tapped == null -> null
    current?.arc == tapped -> null
    else -> HoveredBusyArc(tapped, position)
}

private data class HoveredDetourBody(val body: DetourBody, val position: Offset)

/**
 * A hover tooltip anchored just past the pointer. It rides on the opaque [DayViewColors.panel]
 * surface, but a drop shadow plus a hairline border lift it off the ring so it never reads as a
 * faint, washed-out patch against the dark background.
 */
@Composable
private fun BoxScope.HoverTooltip(
    position: Offset,
    colors: DayViewColors,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset {
                IntOffset(
                    position.x.roundToInt() + 14,
                    position.y.roundToInt() + 14,
                )
            }
            .shadow(12.dp, shape)
            .background(colors.panel, shape)
            .border(1.dp, colors.overlay.copy(alpha = .1f), shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(content = content)
    }
}

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

/**
 * Renders a hero quote. When [source] is blank the quote is a plain line. When a source
 * is present, hovering with a mouse (desktop) or tapping (Android) reveals a dim source
 * line beneath the quote; moving the mouse away hides it again.
 */
@Composable
private fun HeroQuote(
    quote: String,
    source: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (source.isBlank()) {
        Text(
            text = quote,
            color = color,
            fontSize = 22.sp,
            lineHeight = 29.sp,
            fontWeight = FontWeight.Medium,
            modifier = modifier,
        )
        return
    }
    val colors = LocalDayViewColors.current
    var revealed by remember(quote, source) { mutableStateOf(false) }
    Column(
        modifier = modifier
            .pointerInput(source) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val isMouse = event.changes.firstOrNull()?.type == PointerType.Mouse
                        when (event.type) {
                            PointerEventType.Enter, PointerEventType.Move ->
                                if (isMouse) revealed = true
                            PointerEventType.Exit ->
                                if (isMouse) revealed = false
                        }
                    }
                }
            }
            .pointerInput(source) {
                detectTapGestures { revealed = !revealed }
            },
    ) {
        Text(
            text = quote,
            color = color,
            fontSize = 22.sp,
            lineHeight = 29.sp,
            fontWeight = FontWeight.Medium,
        )
        if (revealed) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = source,
                color = colors.muted,
                fontSize = 13.sp,
                letterSpacing = .5.sp,
            )
        }
    }
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
    openDetour: OpenDetourPanelState?,
    onStopOpenDetour: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDayViewColors.current
    Column(modifier = modifier.widthIn(max = 430.dp)) {
        if (openDetour != null) {
            OpenDetourPanel(openDetour, onStopOpenDetour)
        } else {
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
        }
        Spacer(Modifier.height(22.dp))

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
        val heroSources = stringArrayResource(
            when (heroSlot) {
                HeroQuoteSlot.NOT_STARTED -> Res.array.today_hero_not_started_sources
                HeroQuoteSlot.FINISHED -> Res.array.today_hero_finished_sources
                HeroQuoteSlot.ENDING -> Res.array.today_hero_ending_sources
                HeroQuoteSlot.ONGOING -> Res.array.today_hero_ongoing_sources
            },
        )
        val heroIndex = heroQuoteIndex(heroQuotes.size, HeroQuoteSelection.seed(heroSlot))
        HeroQuote(
            quote = heroQuotes[heroIndex],
            source = heroSources.getOrElse(heroIndex) { "" },
            color = colors.cloud,
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
private fun OpenDetourPanel(
    state: OpenDetourPanelState,
    onStop: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(colors.panel, RoundedCornerShape(18.dp))
            .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.detour_section), color = colors.amber, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
            Spacer(Modifier.weight(1f))
            Text(stringResource(Res.string.open_detour_status), color = colors.mint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text(state.category, color = colors.cloud, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
        if (state.description.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(state.description, color = colors.muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatElapsedClock(state.elapsed), color = colors.cloud, fontSize = 34.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.weight(1f))
            FocusStopRoundButton(onStop, Modifier.testTag(DayViewTestTags.OpenDetourStop))
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
    Column(
        modifier = Modifier.fillMaxWidth()
            .startFocusOnCommandEnter(
                enabled = intention.isNotBlank(),
                onStart = onStart,
            ),
    ) {
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
            modifier = Modifier.fillMaxWidth()
                .adjustDurationWithArrowKeys(
                    onDecrease = { if (progress.durationMinutes > 5) onDurationChange(-5) },
                    onIncrease = { if (progress.durationMinutes < 180) onDurationChange(5) },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            TimeButton(
                label = "−",
                enabled = progress.durationMinutes > 5,
                onClickLabel = stringResource(Res.string.focus_duration_decrease),
                valueDescription = stringResource(Res.string.focus_duration_value, progress.durationMinutes.toString()),
                modifier = Modifier.testTag(DayViewTestTags.FocusDurationDecrease),
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
                modifier = Modifier.testTag(DayViewTestTags.FocusDurationIncrease),
            ) { onDurationChange(5) }
        }
        Spacer(Modifier.height(13.dp))
        val startLabel = stringResource(Res.string.focus_start_full_button)
        FocusActionButton(
            if (desktopKeyboardShortcutsEnabled()) "$startLabel · ⌘↩" else startLabel,
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
    val backgroundColor = when {
        !enabled -> color.copy(alpha = .05f)
        filled -> color.copy(alpha = .2f)
        else -> color.copy(alpha = .14f)
    }
    val contentColor = if (enabled) color else color.copy(alpha = .4f)
    val borderColor = when {
        !enabled -> color.copy(alpha = .1f)
        filled -> color.copy(alpha = .6f)
        else -> color.copy(alpha = .38f)
    }
    Box(
        modifier = modifier.minimumInteractiveComponentSize()
            .background(backgroundColor, RoundedCornerShape(12.dp))
            .border(if (filled) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
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
private fun LongTermGoalPanel(
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
