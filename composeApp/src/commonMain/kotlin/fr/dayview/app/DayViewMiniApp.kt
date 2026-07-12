package fr.dayview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
import fr.dayview.app.generated.resources.goal_section_title
import fr.dayview.app.generated.resources.mini_focus_active
import fr.dayview.app.generated.resources.mini_focus_single_thing
import fr.dayview.app.generated.resources.mini_no_goal
import fr.dayview.app.generated.resources.mini_start_focus_label
import fr.dayview.app.generated.resources.mini_stop_focus_label
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
    onStartFocus: (String) -> Unit,
    onStopFocus: () -> Unit,
    onOpenMainWindow: () -> Unit,
) {
    DayViewTheme { colors ->
        var showIntentionModal by remember { mutableStateOf(false) }
        var draftIntention by remember(focusIntention) { mutableStateOf(focusIntention) }

        Surface(modifier = Modifier.fillMaxSize(), color = colors.ink) {
            Box(Modifier.fillMaxSize()) {
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
                    MiniGoal(
                        title = goalTitle,
                        deadline = goalDeadline,
                        now = now,
                        workStartMinutes = progress.startHour * 60 + progress.startMinute,
                        workEndMinutes = progress.endHour * 60 + progress.endMinute,
                    )
                    Spacer(Modifier.height(10.dp))
                    if (pomodoro.status == PomodoroStatus.IDLE) {
                        MiniFocusStart(
                            onClick = {
                                draftIntention = focusIntention
                                showIntentionModal = true
                            },
                        )
                    } else {
                        MiniFocus(
                            progress = pomodoro,
                            intention = focusIntention,
                            onStop = onStopFocus,
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
            )
            Spacer(Modifier.height(3.dp))
            Text(
                title.ifBlank { stringResource(Res.string.mini_no_goal) },
                color = if (title.isBlank()) colors.muted else colors.cloud,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
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
            .background(colors.amber.copy(alpha = .1f), RoundedCornerShape(15.dp))
            .border(1.dp, colors.amber.copy(alpha = .25f), RoundedCornerShape(15.dp))
            .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.mini_start_focus_label), onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(Res.string.focus_start_button),
                color = colors.amber,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                stringResource(Res.string.mini_focus_single_thing),
                color = colors.muted,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text("+", color = colors.amber, fontSize = 26.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun MiniFocus(
    progress: PomodoroProgress,
    intention: String,
    onStop: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    val isBreak = progress.status == PomodoroStatus.BREAK
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(colors.amber.copy(alpha = .1f), RoundedCornerShape(15.dp))
            .border(1.dp, colors.amber.copy(alpha = .25f), RoundedCornerShape(15.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (isBreak) stringResource(Res.string.focus_state_break_active) else stringResource(Res.string.mini_focus_active),
                color = if (isBreak) colors.mint else colors.amber,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                intention.ifBlank { stringResource(Res.string.mini_focus_single_thing) },
                color = colors.cloud,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            if (isBreak) formatBreakClock(progress) else formatPomodoroClock(progress),
            color = colors.cloud,
            fontSize = 24.sp,
            fontWeight = FontWeight.Light,
        )
        Spacer(Modifier.width(12.dp))
        MiniStopButton(onStop)
    }
}

@Composable
private fun MiniStopButton(onStop: () -> Unit) {
    val colors = LocalDayViewColors.current
    Box(
        modifier = Modifier.size(40.dp)
            .background(colors.overlay.copy(alpha = .08f), CircleShape)
            .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.mini_stop_focus_label), onClick = onStop),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(12.dp)
                .background(colors.red.copy(alpha = .85f), RoundedCornerShape(2.dp)),
        )
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
    Box(
        modifier = Modifier.fillMaxSize()
            .background(colors.ink.copy(alpha = .6f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 20.dp)
                .background(colors.panel, RoundedCornerShape(18.dp))
                .border(1.dp, colors.overlay.copy(alpha = .08f), RoundedCornerShape(18.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(18.dp),
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
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FocusActionButton(
                    stringResource(Res.string.focus_cancel_button),
                    colors.muted,
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss,
                )
                FocusActionButton(
                    stringResource(Res.string.focus_start_short_button),
                    colors.amber,
                    modifier = Modifier.weight(1f),
                    enabled = intention.isNotBlank(),
                    filled = true,
                    onClick = onStart,
                )
            }
        }
    }
}
