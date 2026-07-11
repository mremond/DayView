package fr.dayview.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.time.Clock

private data class DayViewColors(
    val ink: Color,
    val panel: Color,
    val cloud: Color,
    val muted: Color,
    val mint: Color,
    val amber: Color,
    val red: Color,
    val glow: Color,
    val overlay: Color,
)

private val DarkDayViewColors = DayViewColors(
    ink = Color(0xFF0B0D12),
    panel = Color(0xFF14171E),
    cloud = Color(0xFFF3F1EB),
    muted = Color(0xFF8B909B),
    mint = Color(0xFF78E6BD),
    amber = Color(0xFFFFB86B),
    red = Color(0xFFFF7272),
    glow = Color(0xFF171B22),
    overlay = Color.White,
)

private val LightDayViewColors = DayViewColors(
    ink = Color(0xFFF4F2EC),
    panel = Color(0xFFFFFFFF),
    cloud = Color(0xFF19201D),
    muted = Color(0xFF68716D),
    mint = Color(0xFF168866),
    amber = Color(0xFFB76218),
    red = Color(0xFFC74646),
    glow = Color(0xFFDCEAE3),
    overlay = Color(0xFF16211D),
)

private val LocalDayViewColors = staticCompositionLocalOf { DarkDayViewColors }

@Composable
fun DayViewApp(preferences: DayPreferences = DefaultDayPreferences) {
    val isDark = isSystemInDarkTheme()
    val colors = if (isDark) DarkDayViewColors else LightDayViewColors
    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = colors.mint,
            background = colors.ink,
            surface = colors.panel,
            onBackground = colors.cloud,
            onSurface = colors.cloud,
            error = colors.red,
        )
    } else {
        lightColorScheme(
            primary = colors.mint,
            background = colors.ink,
            surface = colors.panel,
            onBackground = colors.cloud,
            onSurface = colors.cloud,
            error = colors.red,
        )
    }
    CompositionLocalProvider(LocalDayViewColors provides colors) {
        MaterialTheme(colorScheme = colorScheme) {
            Surface(modifier = Modifier.fillMaxSize(), color = colors.ink) {
            val savedStart = remember(preferences) { preferences.loadStartMinutes().coerceIn(0, 23 * 60 + 29) }
            var startMinutes by remember(preferences) { mutableStateOf(savedStart) }
            var endMinutes by remember(preferences) {
                mutableStateOf(preferences.loadEndMinutes().coerceIn(savedStart + 30, 23 * 60 + 59))
            }
            var nowMillis by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
            var goalTitle by remember(preferences) { mutableStateOf(preferences.loadGoalTitle()) }
            var goalDeadlineMillis by remember(preferences) { mutableStateOf(preferences.loadGoalDeadlineMillis()) }
            var goalDeadlineText by remember(preferences) {
                mutableStateOf(goalDeadlineMillis?.let(::formatGoalDeadline).orEmpty())
            }

            LaunchedEffect(Unit) {
                while (true) {
                    nowMillis = Clock.System.now().toEpochMilliseconds()
                    delay(1_000)
                }
            }

            val progress = calculateDayProgress(nowMillis, startMinutes, endMinutes)
            DayViewScreen(
                progress = progress,
                onMoveStart = { delta ->
                    startMinutes = (startMinutes + delta).coerceIn(0, endMinutes - 30)
                    preferences.saveDayRange(startMinutes, endMinutes)
                },
                onMoveEnd = { delta ->
                    endMinutes = (endMinutes + delta).coerceIn(startMinutes + 30, 23 * 60 + 59)
                    preferences.saveDayRange(startMinutes, endMinutes)
                },
                goalTitle = goalTitle,
                goalDeadlineText = goalDeadlineText,
                goalDeadlineMillis = goalDeadlineMillis,
                nowMillis = nowMillis,
                onGoalTitleChange = { value ->
                    goalTitle = value.take(80)
                    preferences.saveGlobalGoal(goalTitle, goalDeadlineMillis)
                },
                onGoalDeadlineChange = { value ->
                    goalDeadlineText = value.take(16)
                    val parsed = parseGoalDeadline(goalDeadlineText)
                    if (parsed != null || goalDeadlineText.isBlank()) {
                        goalDeadlineMillis = parsed
                        preferences.saveGlobalGoal(goalTitle, goalDeadlineMillis)
                    }
                },
            )
            }
        }
    }
}

@Composable
private fun DayViewScreen(
    progress: DayProgress,
    onMoveStart: (Int) -> Unit,
    onMoveEnd: (Int) -> Unit,
    goalTitle: String,
    goalDeadlineText: String,
    goalDeadlineMillis: Long?,
    nowMillis: Long,
    onGoalTitleChange: (String) -> Unit,
    onGoalDeadlineChange: (String) -> Unit,
) {
    val colors = LocalDayViewColors.current
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(
            Brush.radialGradient(
                colors = listOf(colors.glow, colors.ink),
                radius = 950f,
            ),
        ),
    ) {
        val wide = maxWidth >= 780.dp
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
            Header()
            Spacer(Modifier.height(if (wide) 28.dp else 18.dp))

            if (wide) {
                Row(
                    modifier = Modifier.fillMaxSize().widthIn(max = 1040.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(64.dp),
                ) {
                    CountdownCircle(progress, Modifier.weight(1.15f))
                    SidePanel(
                        progress = progress,
                        onMoveStart = onMoveStart,
                        onMoveEnd = onMoveEnd,
                        goalTitle = goalTitle,
                        goalDeadlineText = goalDeadlineText,
                        goalDeadlineMillis = goalDeadlineMillis,
                        nowMillis = nowMillis,
                        onGoalTitleChange = onGoalTitleChange,
                        onGoalDeadlineChange = onGoalDeadlineChange,
                        modifier = Modifier.weight(.85f).verticalScroll(rememberScrollState()),
                    )
                }
            } else {
                CountdownCircle(progress, Modifier.fillMaxWidth().height(340.dp))
                Spacer(Modifier.height(18.dp))
                SidePanel(
                    progress = progress,
                    onMoveStart = onMoveStart,
                    onMoveEnd = onMoveEnd,
                    goalTitle = goalTitle,
                    goalDeadlineText = goalDeadlineText,
                    goalDeadlineMillis = goalDeadlineMillis,
                    nowMillis = nowMillis,
                    onGoalTitleChange = onGoalTitleChange,
                    onGoalDeadlineChange = onGoalDeadlineChange,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun Header() {
    val colors = LocalDayViewColors.current
    Row(
        modifier = Modifier.fillMaxWidth().widthIn(max = 1040.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(colors.mint, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text("DAYVIEW", color = colors.cloud, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.2.sp)
        Spacer(Modifier.weight(1f))
        Text("AUJOURD’HUI", color = colors.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)
    }
}

@Composable
private fun CountdownCircle(progress: DayProgress, modifier: Modifier = Modifier) {
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
            val circleSize = minOf(maxWidth, maxHeight).coerceIn(260.dp, 510.dp)
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
                        drawArc(
                            brush = Brush.sweepGradient(listOf(accent.copy(alpha = .62f), accent)),
                            startAngle = -90f + (1f - animatedRemaining) * 360f,
                            sweepAngle = animatedRemaining * 360f,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(strokeWidth, cap = StrokeCap.Round),
                        )
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

@Composable
private fun SidePanel(
    progress: DayProgress,
    onMoveStart: (Int) -> Unit,
    onMoveEnd: (Int) -> Unit,
    goalTitle: String,
    goalDeadlineText: String,
    goalDeadlineMillis: Long?,
    nowMillis: Long,
    onGoalTitleChange: (String) -> Unit,
    onGoalDeadlineChange: (String) -> Unit,
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

        Column(
            modifier = Modifier.fillMaxWidth()
                .background(colors.panel, RoundedCornerShape(18.dp))
                .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            TimePreferenceRow(
                label = "DÉBUT DE JOURNÉE",
                hour = progress.startHour,
                minute = progress.startMinute,
                canDecrease = progress.startHour > 0 || progress.startMinute > 0,
                canIncrease = progress.startHour * 60 + progress.startMinute < progress.endHour * 60 + progress.endMinute - 30,
                onMove = onMoveStart,
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.overlay.copy(alpha = .06f)))
            TimePreferenceRow(
                label = "FIN DE JOURNÉE",
                hour = progress.endHour,
                minute = progress.endMinute,
                canDecrease = progress.endHour * 60 + progress.endMinute > progress.startHour * 60 + progress.startMinute + 30,
                canIncrease = progress.endHour < 23 || progress.endMinute < 29,
                onMove = onMoveEnd,
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (progress.isFinished) colors.red else colors.mint, CircleShape))
            Spacer(Modifier.width(9.dp))
            Text(
                if (progress.isFinished) "0 % de la journée disponible" else "${progress.percentageRemaining} % de la journée disponible",
                color = colors.muted,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(18.dp))
        GlobalGoalPanel(
            title = goalTitle,
            deadlineText = goalDeadlineText,
            deadlineMillis = goalDeadlineMillis,
            nowMillis = nowMillis,
            workStartMinutes = progress.startHour * 60 + progress.startMinute,
            workEndMinutes = progress.endHour * 60 + progress.endMinute,
            onTitleChange = onGoalTitleChange,
            onDeadlineChange = onGoalDeadlineChange,
        )
        Spacer(Modifier.height(22.dp))
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
        Spacer(Modifier.height(12.dp))
        GoalTextField(
            value = title,
            placeholder = "Que voulez-vous accomplir ?",
            onValueChange = onTitleChange,
        )
        Spacer(Modifier.height(9.dp))
        GoalTextField(
            value = deadlineText,
            placeholder = GOAL_DATE_PLACEHOLDER,
            onValueChange = onDeadlineChange,
            isError = !deadlineIsValid,
        )
        if (!deadlineIsValid) {
            Spacer(Modifier.height(6.dp))
            Text("Format attendu : $GOAL_DATE_PLACEHOLDER", color = colors.red, fontSize = 10.sp)
        }
    }
}

@Composable
private fun GoalTextField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    isError: Boolean = false,
) {
    val colors = LocalDayViewColors.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = colors.cloud, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        cursorBrush = Brush.verticalGradient(listOf(colors.mint, colors.mint)),
        modifier = Modifier.fillMaxWidth()
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
private fun TimePreferenceRow(
    label: String,
    hour: Int,
    minute: Int,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onMove: (Int) -> Unit,
) {
    val colors = LocalDayViewColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(label, color = colors.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
            Spacer(Modifier.height(3.dp))
            Text(
                "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}",
                color = colors.cloud,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.weight(1f))
        TimeButton("−", enabled = canDecrease) { onMove(-30) }
        Spacer(Modifier.width(8.dp))
        TimeButton("+", enabled = canIncrease) { onMove(30) }
    }
}

@Composable
private fun TimeButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalDayViewColors.current
    val color = if (enabled) colors.cloud else colors.muted.copy(alpha = .4f)
    Box(
        modifier = Modifier.size(42.dp)
            .background(colors.overlay.copy(alpha = if (enabled) .08f else .025f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = 23.sp, fontWeight = FontWeight.Light)
    }
}
