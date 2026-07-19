package fr.dayview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.desktop_open_full_window
import fr.dayview.app.generated.resources.focus_cancel_button
import fr.dayview.app.generated.resources.focus_intention_label
import fr.dayview.app.generated.resources.focus_intention_placeholder
import fr.dayview.app.generated.resources.focus_intention_prompt
import fr.dayview.app.generated.resources.focus_section
import fr.dayview.app.generated.resources.focus_start_button
import fr.dayview.app.generated.resources.focus_start_short_button
import fr.dayview.app.generated.resources.focus_state_break_active
import fr.dayview.app.generated.resources.focus_state_overtime
import fr.dayview.app.generated.resources.goal_section_title
import fr.dayview.app.generated.resources.mini_focus_active
import fr.dayview.app.generated.resources.mini_focus_single_thing
import fr.dayview.app.generated.resources.mini_no_goal
import fr.dayview.app.generated.resources.mini_start_focus_label
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

@Composable
fun DayViewMiniApp(
    progress: DayProgress,
    showSeconds: Boolean,
    now: Instant,
    goalTitle: String,
    goalDeadline: Instant?,
    pomodoro: PomodoroProgress,
    focusIntention: String,
    // A detour may be declared during a session, but not the reverse: startPomodoro refuses
    // while a detour runs. The mini window has no detour UI of its own, so while one runs it
    // simply offers no Start affordance rather than implying it can start a session on top.
    openDetourRunning: Boolean = false,
    recentDetourCategories: List<String> = emptyList(),
    fontScale: Float = 1f,
    onStartFocus: (String) -> Unit,
    onCloseFocus: (FocusClosureOutcome, String, String, String) -> Unit,
    onOpenMainWindow: () -> Unit,
) {
    DayViewTheme(uses24Hour = rememberUses24HourClock()) { colors ->
        var showIntentionModal by remember { mutableStateOf(false) }
        var draftIntention by remember(focusIntention) { mutableStateOf(focusIntention) }
        val baseDensity = LocalDensity.current
        val safeFontScale = fontScale.coerceIn(1f, DISPLAY_SCALE_MAX)

        CompositionLocalProvider(
            LocalDensity provides Density(baseDensity.density, baseDensity.fontScale * safeFontScale),
            LocalPreferenceFontScale provides safeFontScale,
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = colors.ink) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val showGoal = showGoalInMiniWindow(maxHeight.value, safeFontScale)
                    Column(
                        modifier = Modifier.fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(colors.glow, colors.ink),
                                    radius = 650f,
                                ),
                            )
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CountdownCircle(
                            progress = progress,
                            showSeconds = showSeconds,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                        if (showGoal) {
                            MiniGoal(
                                title = goalTitle,
                                deadline = goalDeadline,
                                now = now,
                                workStartMinutes = progress.startHour * 60 + progress.startMinute,
                                workEndMinutes = progress.endHour * 60 + progress.endMinute,
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        if (pomodoro.status == PomodoroStatus.IDLE) {
                            // No detour UI here by design (YAGNI) — while one runs, the Start
                            // affordance simply does not render, matching startPomodoro's
                            // refusal to open a session on top of a detour. The reverse is
                            // allowed, so a session already running keeps its panel below.
                            if (!openDetourRunning) {
                                MiniFocusStart(
                                    onClick = {
                                        draftIntention = focusIntention
                                        showIntentionModal = true
                                    },
                                )
                            }
                        } else {
                            MiniFocus(
                                progress = pomodoro,
                                intention = focusIntention,
                                recentDetourCategories = recentDetourCategories,
                                openDetourRunning = openDetourRunning,
                                onRelaunch = { onStartFocus(focusIntention) },
                                onClose = onCloseFocus,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .testTag(DayViewTestTags.OpenMainWindow)
                            .minimumInteractiveComponentSize()
                            .clickable(
                                role = Role.Button,
                                onClickLabel = stringResource(Res.string.desktop_open_full_window),
                                onClick = onOpenMainWindow,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        ExpandWindowGlyph(color = colors.muted)
                    }
                    if (showIntentionModal) {
                        FocusIntentionModal(
                            intention = draftIntention,
                            onIntentionChange = { draftIntention = it },
                            onStart = {
                                onStartFocus(draftIntention)
                                showIntentionModal = false
                            },
                            onDismiss = { showIntentionModal = false },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniGoal(
    title: String,
    deadline: Instant?,
    now: Instant,
    workStartMinutes: Int,
    workEndMinutes: Int,
) {
    val colors = LocalDayViewColors.current
    val remaining = deadline?.let {
        goalWorkingTimeLabel(
            working = calculateGoalWorkingTime(
                now = now,
                deadline = it,
                startMinutesOfDay = workStartMinutes,
                endMinutesOfDay = workEndMinutes,
            ),
            deadlineReached = it <= now,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .testTag(DayViewTestTags.MiniGoal)
            .background(colors.panel, RoundedCornerShape(15.dp))
            .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(15.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(Res.string.goal_section_title),
                color = colors.mint,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                title.ifBlank { stringResource(Res.string.mini_no_goal) },
                color = if (title.isBlank()) colors.muted else colors.cloud,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        remaining?.let {
            Spacer(Modifier.width(12.dp))
            Text(it, color = colors.muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun MiniFocusStart(onClick: () -> Unit) {
    val colors = LocalDayViewColors.current
    Row(
        modifier = Modifier.fillMaxWidth()
            .testTag(DayViewTestTags.MiniFocusStart)
            .background(colors.amber.copy(alpha = .1f), RoundedCornerShape(15.dp))
            .border(1.dp, colors.amber.copy(alpha = .25f), RoundedCornerShape(15.dp))
            .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.mini_start_focus_label), onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.focus_start_button),
            color = colors.amber,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text("+", color = colors.amber, fontSize = 20.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun MiniFocus(
    progress: PomodoroProgress,
    intention: String,
    recentDetourCategories: List<String>,
    // Relaunching funnels into the same start guard as the IDLE affordance: startPomodoro
    // refuses to open a session while a detour runs (the reverse is allowed), so it hides
    // on the same condition.
    openDetourRunning: Boolean,
    onRelaunch: () -> Unit,
    onClose: (FocusClosureOutcome, String, String, String) -> Unit,
) {
    val colors = LocalDayViewColors.current
    val isBreak = progress.status == PomodoroStatus.BREAK
    val isOvertime = progress.status == PomodoroStatus.OVERTIME
    // Leaving a running session only unfolds the closure sheet; the stage change folds it back.
    var showEarlyClosure by remember(progress.status) { mutableStateOf(false) }
    BoxWithConstraints {
        // On a narrow card the fixed clock and buttons would starve the label
        // column; shrink them so the label keeps readable width.
        val compact = maxWidth < 250.dp
        val gap = if (compact) 8.dp else 12.dp
        val buttonSize = if (compact) 32.dp else 40.dp
        Column(
            modifier = Modifier.fillMaxWidth()
                .background(colors.amber.copy(alpha = .1f), RoundedCornerShape(15.dp))
                .border(1.dp, colors.amber.copy(alpha = .25f), RoundedCornerShape(15.dp))
                .padding(horizontal = if (compact) 12.dp else 14.dp, vertical = 11.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        when {
                            isBreak -> stringResource(Res.string.focus_state_break_active)
                            isOvertime -> stringResource(Res.string.focus_state_overtime)
                            else -> stringResource(Res.string.mini_focus_active)
                        },
                        color = if (isBreak || isOvertime) colors.mint else colors.amber,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        intention.ifBlank { stringResource(Res.string.mini_focus_single_thing) },
                        color = colors.cloud,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(gap))
                // Overtime reads as a headline rather than a clock: the time still counts.
                val clockText = when (progress.status) {
                    PomodoroStatus.OVERTIME -> formatOvertimeLabel(progress)
                    PomodoroStatus.BREAK -> formatBreakClock(progress)
                    else -> formatPomodoroClock(progress)
                }
                Text(
                    clockText,
                    color = if (isOvertime) colors.mint else colors.cloud,
                    fontSize = if (compact) 18.sp else 24.sp,
                    fontWeight = FontWeight.Light,
                )
                if (isBreak && !openDetourRunning) {
                    Spacer(Modifier.width(gap))
                    FocusRelaunchRoundButton(
                        onRelaunch,
                        Modifier.testTag(DayViewTestTags.MiniFocusRelaunch),
                        size = buttonSize,
                    )
                }
                // The closure is the only way out of a running session; in BREAK there is
                // nothing left to stop.
                if (progress.status == PomodoroStatus.ACTIVE && !showEarlyClosure) {
                    Spacer(Modifier.width(gap))
                    FocusStopRoundButton(
                        onStop = { showEarlyClosure = true },
                        modifier = Modifier.testTag(DayViewTestTags.MiniFocusStop),
                        size = buttonSize,
                    )
                }
            }
            // One call site across the crossing, as in the main window: the sheet keeps its
            // identity when the term is reached mid-closure, and the toll it charges is read
            // from the status rather than from the tap that opened it.
            if (isOvertime || showEarlyClosure) {
                Spacer(Modifier.height(11.dp))
                FocusClosureContent(
                    intention = intention,
                    // Before the term leaving costs a name; past it the closure is free. A
                    // detour already running is also free: it already is the named exit, and
                    // its motif is collected when it stops rather than duplicated here.
                    requiresDetourFor = { !isOvertime && it != FocusClosureOutcome.COMPLETED && !openDetourRunning },
                    recentDetourCategories = recentDetourCategories,
                    onClose = onClose,
                    // Overtime has nothing to cancel: the sheet is the panel, not an exit.
                    onCancel = { showEarlyClosure = false }.takeIf { !isOvertime },
                )
            }
        }
    }
}

@Composable
private fun FocusIntentionModal(
    intention: String,
    onIntentionChange: (String) -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
            .background(colors.ink.copy(alpha = .6f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val compactWidth = maxWidth < 280.dp
        val compactHeight = maxHeight < 400.dp
        val outerHorizontalPadding = if (compactWidth) 8.dp else 20.dp
        val outerVerticalPadding = if (compactHeight) 8.dp else 0.dp
        val cardPadding = if (compactWidth || compactHeight) 12.dp else 18.dp
        val cardAlignment = if (compactHeight) Alignment.TopCenter else Alignment.Center

        Column(
            modifier = Modifier.fillMaxWidth()
                .align(cardAlignment)
                .padding(horizontal = outerHorizontalPadding, vertical = outerVerticalPadding)
                .background(colors.panel, RoundedCornerShape(18.dp))
                .border(1.dp, colors.overlay.copy(alpha = .08f), RoundedCornerShape(18.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .verticalScroll(rememberScrollState())
                .padding(cardPadding)
                .testTag(DayViewTestTags.MiniFocusModal),
        ) {
            Text(
                stringResource(Res.string.focus_section),
                color = colors.amber,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.3.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(Res.string.focus_intention_prompt),
                color = colors.muted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(8.dp))
            GoalTextField(
                value = intention,
                semanticLabel = stringResource(Res.string.focus_intention_label),
                placeholder = stringResource(Res.string.focus_intention_placeholder),
                onValueChange = onIntentionChange,
            )
            Spacer(Modifier.height(if (compactHeight) 10.dp else 14.dp))
            if (compactWidth) {
                FocusActionButton(
                    stringResource(Res.string.focus_start_short_button),
                    colors.amber,
                    // Entering focus is free: the intention is no longer a toll on Start.
                    modifier = Modifier.fillMaxWidth().testTag(DayViewTestTags.MiniFocusConfirm),
                    filled = true,
                    onClick = onStart,
                )
                Spacer(Modifier.height(8.dp))
                FocusActionButton(
                    stringResource(Res.string.focus_cancel_button),
                    colors.muted,
                    modifier = Modifier.fillMaxWidth().testTag(DayViewTestTags.MiniFocusCancel),
                    onClick = onDismiss,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FocusActionButton(
                        stringResource(Res.string.focus_cancel_button),
                        colors.muted,
                        modifier = Modifier.weight(1f).testTag(DayViewTestTags.MiniFocusCancel),
                        onClick = onDismiss,
                    )
                    FocusActionButton(
                        stringResource(Res.string.focus_start_short_button),
                        colors.amber,
                        // Entering focus is free: the intention is no longer a toll on Start.
                        modifier = Modifier.weight(1f).testTag(DayViewTestTags.MiniFocusConfirm),
                        filled = true,
                        onClick = onStart,
                    )
                }
            }
        }
    }
}
