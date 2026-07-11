package fr.dayview.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

internal data class DayViewScreenActions(
    val openSettings: () -> Unit,
    val openMiniWindow: (() -> Unit)?,
    val changeGoalTitle: (String) -> Unit,
    val changeGoalDeadline: (String) -> Unit,
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
                        CountdownCircle(progress, state.showSeconds, Modifier.weight(1f).fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                        GlobalGoalPanel(
                            title = state.goalTitle,
                            deadlineText = state.goalDeadlineText,
                            deadlineMillis = state.goalDeadlineMillis,
                            nowMillis = state.nowMillis,
                            workStartMinutes = progress.startHour * 60 + progress.startMinute,
                            workEndMinutes = progress.endHour * 60 + progress.endMinute,
                            onTitleChange = actions.changeGoalTitle,
                            onDeadlineChange = actions.changeGoalDeadline,
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
                CountdownCircle(progress, state.showSeconds, Modifier.fillMaxWidth().height(compactCountdownHeight))
                Spacer(Modifier.height(12.dp))
                GlobalGoalPanel(
                    title = state.goalTitle,
                    deadlineText = state.goalDeadlineText,
                    deadlineMillis = state.goalDeadlineMillis,
                    nowMillis = state.nowMillis,
                    workStartMinutes = progress.startHour * 60 + progress.startMinute,
                    workEndMinutes = progress.endHour * 60 + progress.endMinute,
                    onTitleChange = actions.changeGoalTitle,
                    onDeadlineChange = actions.changeGoalDeadline,
                )
                Spacer(Modifier.height(18.dp))
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
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
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
        Text("DAYVIEW", color = colors.cloud, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.2.sp)
        Spacer(Modifier.weight(1f))
        onOpenMiniWindow?.let {
            Text(
                "MINI",
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
            "RÉGLAGES",
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

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val circleSize = minOf(maxWidth, maxHeight, 510.dp)
            Box(Modifier.size(circleSize), contentAlignment = Alignment.Center) {
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
                        drawArc(
                            brush = Brush.sweepGradient(listOf(accent.copy(alpha = .62f), accent)),
                            startAngle = momentAngle,
                            sweepAngle = animatedRemaining * 360f,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(strokeWidth, cap = StrokeCap.Round),
                        )

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
                        if (progress.isFinished) "JOURNÉE\nTERMINÉE" else "IL RESTE",
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
                                "${progress.remainingSeconds.toString().padStart(2, '0')} secondes",
                                color = colors.muted,
                                fontSize = 12.sp,
                                letterSpacing = .8.sp,
                            )
                        }
                    }
                }
            }
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
    modifier: Modifier = Modifier,
) {
    val colors = LocalDayViewColors.current
    Column(modifier = modifier.widthIn(max = 430.dp)) {
        Text(
            text = when {
                !progress.hasStarted -> "Votre journée n’a pas commencé.\nLe cercle est encore intact."
                progress.isFinished -> "Le temps prévu est écoulé.\nVous pouvez relâcher la journée."
                progress.remainingRatio < .2f -> "La journée touche à sa fin.\nChoisissez une seule chose essentielle."
                else -> "Voyez le temps.\nGardez le cap, sans pression."
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
                if (progress.isFinished) "0 % de la journée disponible" else "${progress.percentageRemaining} % de la journée disponible",
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
            Text("FOCUS", color = colors.amber, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
            Spacer(Modifier.weight(1f))
            Text(
                when (progress.status) {
                    PomodoroStatus.IDLE -> "PRÊT À S’ENGAGER"
                    PomodoroStatus.ACTIVE -> "EN COURS"
                    PomodoroStatus.BREAK -> if (progress.breakElapsedMillis >= 60 * 60_000L) {
                        "SÉRIE INACTIVE"
                    } else {
                        "PAUSE EN COURS"
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
                        "VOTRE POINT DE REPRISE",
                        color = colors.mint,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        intention.ifBlank { "Une seule chose à la fois." },
                        color = colors.cloud,
                        fontSize = 16.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "Il reste ${formatPomodoroClock(progress)} pour garder le cap.",
                        color = colors.muted,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(11.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        FocusActionButton(
                            "ARRÊTER",
                            colors.red,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onStop()
                                onDismissResumeRitual()
                            },
                        )
                        FocusActionButton(
                            "REPRENDRE",
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
                        "REVENIR À L’ESSENTIEL",
                        color = colors.amber,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        intention.ifBlank { "Une seule chose à la fois." },
                        color = colors.cloud,
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(10.dp))
                    FocusActionButton("C’EST REPARTI", colors.amber, onClick = onDismissDriftReminder)
                }
                Spacer(Modifier.height(12.dp))
            }
            Text(
                "MON INTENTION",
                color = colors.muted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                intention.ifBlank { "Une seule chose à la fois." },
                color = colors.cloud,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatPomodoroClock(progress), color = colors.cloud, fontSize = 34.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.weight(1f))
                FocusActionButton("ARRÊTER", colors.red, onClick = onStop)
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
                if (progress.breakElapsedMillis >= 60 * 60_000L) "SÉRIE INACTIVE" else "PAUSE DEPUIS",
                color = colors.muted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(5.dp))
            Text(formatBreakClock(progress), color = colors.cloud, fontSize = 34.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(14.dp))
            Text(
                if (progress.breakElapsedMillis < 10 * 60_000L) {
                    "PRENEZ LE TEMPS DE DÉCONNECTER"
                } else {
                    "REPRENDRE RESTE UN CHOIX CONSCIENT"
                },
                color = colors.muted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = .9.sp,
            )
            Spacer(Modifier.height(9.dp))
            FocusActionButton(
                "REPRENDRE UN FOCUS",
                colors.mint,
                modifier = Modifier.fillMaxWidth(),
                filled = true,
                onClick = onStart,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "CLÔTURER CE FOCUS",
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
                    "TERMINÉ",
                    colors.mint,
                    modifier = Modifier.weight(1f),
                    onClick = { onClose(FocusClosureOutcome.COMPLETED) },
                )
                FocusActionButton(
                    "AVANCÉ",
                    colors.amber,
                    modifier = Modifier.weight(1f),
                    onClick = { onClose(FocusClosureOutcome.PROGRESSED) },
                )
                FocusActionButton(
                    "À REPRENDRE",
                    colors.muted,
                    modifier = Modifier.weight(1f),
                    onClick = { onClose(FocusClosureOutcome.TO_RESUME) },
                )
            }
        } else {
            if (lastClosure != null) {
                val closureLabel = when (lastClosure) {
                    FocusClosureOutcome.COMPLETED -> "TERMINÉ"
                    FocusClosureOutcome.PROGRESSED -> "AVANCÉ"
                    FocusClosureOutcome.TO_RESUME -> "À REPRENDRE"
                }
                Text(
                    "FOCUS CLÔTURÉ · $closureLabel",
                    color = colors.mint,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = .9.sp,
                )
                Spacer(Modifier.height(10.dp))
            }
            Text(
                "À LA FIN DE CE FOCUS, J’AURAI…",
                color = colors.muted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(7.dp))
            GoalTextField(
                value = intention,
                semanticLabel = "Intention du Focus",
                placeholder = "Ex. terminé le plan de la présentation",
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
                    onClickLabel = "Diminuer la durée du Focus de 5 minutes",
                    valueDescription = "Durée du Focus : ${progress.durationMinutes} minutes",
                ) { onDurationChange(-5) }
                Spacer(Modifier.width(18.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(progress.durationMinutes.toString(), color = colors.cloud, fontSize = 28.sp, fontWeight = FontWeight.Light)
                    Text("MINUTES", color = colors.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                Spacer(Modifier.width(18.dp))
                TimeButton(
                    label = "+",
                    enabled = progress.durationMinutes < 180,
                    onClickLabel = "Augmenter la durée du Focus de 5 minutes",
                    valueDescription = "Durée du Focus : ${progress.durationMinutes} minutes",
                ) { onDurationChange(5) }
            }
            Spacer(Modifier.height(13.dp))
            FocusActionButton(
                "DÉMARRER LE FOCUS",
                colors.amber,
                modifier = Modifier.fillMaxWidth(),
                enabled = intention.isNotBlank(),
                filled = true,
                onClick = onStart,
            )
            if (intention.isBlank()) {
                Spacer(Modifier.height(7.dp))
                Text("Écrivez une intention pour démarrer.", color = colors.muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun FocusActionButton(
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
    deadlineText: String,
    deadlineMillis: Long?,
    nowMillis: Long,
    workStartMinutes: Int,
    workEndMinutes: Int,
    onTitleChange: (String) -> Unit,
    onDeadlineChange: (String) -> Unit,
) {
    val colors = LocalDayViewColors.current
    val deadlineIsValid = deadlineText.isBlank() || parseGoalDeadline(deadlineText) != null
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(colors.panel, RoundedCornerShape(18.dp))
            .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("OBJECTIF GLOBAL", color = colors.mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
            Spacer(Modifier.weight(1f))
            if (deadlineMillis != null) {
                val workingMillis = remember(
                    nowMillis / 60_000,
                    deadlineMillis,
                    workStartMinutes,
                    workEndMinutes,
                ) {
                    calculateGoalWorkingMillis(
                        nowMillis = nowMillis,
                        deadlineMillis = deadlineMillis,
                        startMinutesOfDay = workStartMinutes,
                        endMinutesOfDay = workEndMinutes,
                    )
                }
                Text(
                    formatGoalWorkingHours(workingMillis, deadlineMillis <= nowMillis).uppercase(),
                    color = if (deadlineMillis <= nowMillis) colors.red else colors.muted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = .7.sp,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (maxWidth >= 360.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    GoalTextField(
                        value = title,
                        semanticLabel = "Objectif du jour",
                        placeholder = "Que voulez-vous accomplir ?",
                        onValueChange = onTitleChange,
                        imeAction = ImeAction.Next,
                        modifier = Modifier.weight(1f),
                    )
                    GoalTextField(
                        value = deadlineText,
                        semanticLabel = "Date limite de l’objectif",
                        placeholder = GOAL_DATE_PLACEHOLDER,
                        onValueChange = { onDeadlineChange(formatGoalDeadlineInput(it)) },
                        isError = !deadlineIsValid,
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.width(148.dp),
                    )
                }
            } else {
                Column {
                    GoalTextField(
                        value = title,
                        semanticLabel = "Objectif du jour",
                        placeholder = "Que voulez-vous accomplir ?",
                        onValueChange = onTitleChange,
                        imeAction = ImeAction.Next,
                    )
                    Spacer(Modifier.height(9.dp))
                    GoalTextField(
                        value = deadlineText,
                        semanticLabel = "Date limite de l’objectif",
                        placeholder = GOAL_DATE_PLACEHOLDER,
                        onValueChange = { onDeadlineChange(formatGoalDeadlineInput(it)) },
                        isError = !deadlineIsValid,
                        keyboardType = KeyboardType.Number,
                    )
                }
            }
        }
        if (!deadlineIsValid) {
            Spacer(Modifier.height(6.dp))
            Text("Format attendu : $GOAL_DATE_PLACEHOLDER", color = colors.red, fontSize = 10.sp)
        }
    }
}

@Composable
private fun GoalTextField(
    value: String,
    semanticLabel: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDayViewColors.current
    val focusManager = LocalFocusManager.current
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
