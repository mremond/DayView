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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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

private enum class DayViewDestination {
    TODAY,
    SETTINGS,
}

@Composable
fun DayViewApp(
    preferences: DayPreferences = DefaultDayPreferences,
    onOpenMiniWindow: (() -> Unit)? = null,
    showFocusDriftReminder: Boolean = false,
    onDismissFocusDriftReminder: () -> Unit = {},
    showFocusResumeRitual: Boolean = false,
    onDismissFocusResumeRitual: () -> Unit = {},
    scheduleSoundAlerts: Boolean = true,
) {
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
            var pomodoroMinutes by remember(preferences) {
                mutableStateOf(preferences.loadPomodoroMinutes().coerceIn(5, 180))
            }
            var pomodoroEndMillis by remember(preferences) {
                mutableStateOf(preferences.loadPomodoroEndMillis())
            }
            var focusIntention by remember(preferences) {
                mutableStateOf(preferences.loadFocusIntention())
            }
            var lastFocusClosure by remember { mutableStateOf<FocusClosureOutcome?>(null) }
            var destination by remember { mutableStateOf(DayViewDestination.TODAY) }
            var showSeconds by remember(preferences) { mutableStateOf(preferences.loadShowSeconds()) }
            var soundSettings by remember(preferences) { mutableStateOf(preferences.loadSoundSettings()) }
            val soundPlayer = remember { createSoundCuePlayer() }
            val soundScheduler = remember { SoundAlertScheduler() }

            DisposableEffect(soundPlayer) {
                onDispose { soundPlayer.close() }
            }

            LaunchedEffect(showSeconds, pomodoroEndMillis) {
                while (true) {
                    nowMillis = Clock.System.now().toEpochMilliseconds()
                    val focusIsActive = pomodoroEndMillis?.let { it > nowMillis } == true
                    val refreshDelay = if (showSeconds || focusIsActive) {
                        1_000L
                    } else {
                        60_000L - nowMillis % 60_000L
                    }
                    delay(refreshDelay)
                }
            }

            val dayNowMillis = if (showSeconds) nowMillis else nowMillis - nowMillis % 60_000L
            val progress = calculateDayProgress(dayNowMillis, startMinutes, endMinutes)
            val pomodoro = calculatePomodoroProgress(nowMillis, pomodoroMinutes, pomodoroEndMillis)
            LaunchedEffect(nowMillis, startMinutes, endMinutes, soundSettings, scheduleSoundAlerts) {
                if (scheduleSoundAlerts) {
                    val cue = soundScheduler.observe(
                        nowMillis = nowMillis,
                        startMinutesOfDay = startMinutes,
                        endMinutesOfDay = endMinutes,
                        intervalMinutes = soundSettings.intervalMinutes,
                    )
                    if (cue != null && soundSettings.allows(cue)) {
                        soundPlayer.play(cue, soundSettings.volumePercent / 100f)
                    }
                }
            }
            val onMoveStart: (Int) -> Unit = { delta ->
                startMinutes = (startMinutes + delta).coerceIn(0, endMinutes - 30)
                preferences.saveDayRange(startMinutes, endMinutes)
            }
            val onMoveEnd: (Int) -> Unit = { delta ->
                endMinutes = (endMinutes + delta).coerceIn(startMinutes + 30, 23 * 60 + 59)
                preferences.saveDayRange(startMinutes, endMinutes)
            }

            if (destination == DayViewDestination.SETTINGS) {
                SettingsScreen(
                    progress = progress,
                    onMoveStart = onMoveStart,
                    onMoveEnd = onMoveEnd,
                    showSeconds = showSeconds,
                    onShowSecondsChange = { enabled ->
                        showSeconds = enabled
                        preferences.saveShowSeconds(enabled)
                    },
                    soundSettings = soundSettings,
                    onSoundSettingsChange = { updated ->
                        soundSettings = updated.normalized()
                        preferences.saveSoundSettings(soundSettings)
                    },
                    onPreviewSound = { cue ->
                        soundPlayer.play(cue, soundSettings.volumePercent / 100f)
                    },
                    onBack = { destination = DayViewDestination.TODAY },
                )
            } else {
                DayViewScreen(
                    progress = progress,
                    showSeconds = showSeconds,
                    onOpenSettings = { destination = DayViewDestination.SETTINGS },
                    onOpenMiniWindow = onOpenMiniWindow,
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
                    pomodoro = pomodoro,
                    focusIntention = focusIntention,
                    lastFocusClosure = lastFocusClosure,
                    onFocusIntentionChange = { value ->
                        focusIntention = value.take(100)
                        preferences.saveFocusIntention(focusIntention)
                        lastFocusClosure = null
                    },
                    showFocusDriftReminder = showFocusDriftReminder,
                    onDismissFocusDriftReminder = onDismissFocusDriftReminder,
                    showFocusResumeRitual = showFocusResumeRitual,
                    onDismissFocusResumeRitual = onDismissFocusResumeRitual,
                    onPomodoroDurationChange = { delta ->
                        if (pomodoro.status != PomodoroStatus.ACTIVE) {
                            pomodoroMinutes = (pomodoroMinutes + delta).coerceIn(5, 180)
                            preferences.savePomodoro(pomodoroMinutes, null)
                        }
                    },
                    onPomodoroStart = {
                        if (focusIntention.isNotBlank()) {
                            lastFocusClosure = null
                            pomodoroEndMillis = nowMillis + pomodoroMinutes * 60_000L
                            preferences.savePomodoro(pomodoroMinutes, pomodoroEndMillis)
                        }
                    },
                    onPomodoroStop = {
                        pomodoroEndMillis = null
                        preferences.savePomodoro(pomodoroMinutes, null)
                    },
                    onPomodoroClose = { outcome ->
                        pomodoroEndMillis = null
                        preferences.savePomodoro(pomodoroMinutes, null)
                        if (!outcome.keepsIntention) {
                            focusIntention = ""
                            preferences.saveFocusIntention("")
                        }
                        lastFocusClosure = outcome
                    },
                )
            }
            }
        }
    }
}

@Composable
fun DayViewMiniApp(
    progress: DayProgress,
    showSeconds: Boolean,
    nowMillis: Long,
    goalTitle: String,
    goalDeadlineMillis: Long?,
    pomodoro: PomodoroProgress,
    focusIntention: String,
) {
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
                        deadlineMillis = goalDeadlineMillis,
                        nowMillis = nowMillis,
                        workStartMinutes = progress.startHour * 60 + progress.startMinute,
                        workEndMinutes = progress.endHour * 60 + progress.endMinute,
                    )
                    if (pomodoro.status == PomodoroStatus.ACTIVE) {
                        Spacer(Modifier.height(10.dp))
                        MiniFocus(pomodoro, focusIntention)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniGoal(
    title: String,
    deadlineMillis: Long?,
    nowMillis: Long,
    workStartMinutes: Int,
    workEndMinutes: Int,
) {
    val colors = LocalDayViewColors.current
    val remaining = deadlineMillis?.let {
        formatGoalWorkingHours(
            workingMillis = calculateGoalWorkingMillis(
                nowMillis = nowMillis,
                deadlineMillis = it,
                startMinutesOfDay = workStartMinutes,
                endMinutesOfDay = workEndMinutes,
            ),
            deadlineReached = it <= nowMillis,
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
                "OBJECTIF GLOBAL",
                color = colors.mint,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                title.ifBlank { "Aucun objectif défini" },
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
private fun MiniFocus(progress: PomodoroProgress, intention: String) {
    val colors = LocalDayViewColors.current
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(colors.amber.copy(alpha = .1f), RoundedCornerShape(15.dp))
            .border(1.dp, colors.amber.copy(alpha = .25f), RoundedCornerShape(15.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "FOCUS EN COURS",
                color = colors.amber,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                intention.ifBlank { "Une seule chose à la fois" },
                color = colors.cloud,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            formatPomodoroClock(progress),
            color = colors.cloud,
            fontSize = 24.sp,
            fontWeight = FontWeight.Light,
        )
    }
}

@Composable
private fun DayViewScreen(
    progress: DayProgress,
    showSeconds: Boolean,
    onOpenSettings: () -> Unit,
    onOpenMiniWindow: (() -> Unit)?,
    goalTitle: String,
    goalDeadlineText: String,
    goalDeadlineMillis: Long?,
    nowMillis: Long,
    onGoalTitleChange: (String) -> Unit,
    onGoalDeadlineChange: (String) -> Unit,
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
            Header(onOpenSettings, onOpenMiniWindow)
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
                        CountdownCircle(progress, showSeconds, Modifier.weight(1f).fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
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
                    }
                    SidePanel(
                        progress = progress,
                        pomodoro = pomodoro,
                        focusIntention = focusIntention,
                        lastFocusClosure = lastFocusClosure,
                        onFocusIntentionChange = onFocusIntentionChange,
                        showFocusDriftReminder = showFocusDriftReminder,
                        onDismissFocusDriftReminder = onDismissFocusDriftReminder,
                        showFocusResumeRitual = showFocusResumeRitual,
                        onDismissFocusResumeRitual = onDismissFocusResumeRitual,
                        onPomodoroDurationChange = onPomodoroDurationChange,
                        onPomodoroStart = onPomodoroStart,
                        onPomodoroStop = onPomodoroStop,
                        onPomodoroClose = onPomodoroClose,
                        modifier = Modifier.weight(.85f).verticalScroll(rememberScrollState()),
                    )
                }
            } else {
                CountdownCircle(progress, showSeconds, Modifier.fillMaxWidth().height(340.dp))
                Spacer(Modifier.height(12.dp))
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
                Spacer(Modifier.height(18.dp))
                SidePanel(
                    progress = progress,
                    pomodoro = pomodoro,
                    focusIntention = focusIntention,
                    lastFocusClosure = lastFocusClosure,
                    onFocusIntentionChange = onFocusIntentionChange,
                    showFocusDriftReminder = showFocusDriftReminder,
                    onDismissFocusDriftReminder = onDismissFocusDriftReminder,
                    showFocusResumeRitual = showFocusResumeRitual,
                    onDismissFocusResumeRitual = onDismissFocusResumeRitual,
                    onPomodoroDurationChange = onPomodoroDurationChange,
                    onPomodoroStart = onPomodoroStart,
                    onPomodoroStop = onPomodoroStop,
                    onPomodoroClose = onPomodoroClose,
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
                modifier = Modifier.clickable(onClick = it).padding(vertical = 10.dp, horizontal = 4.dp),
            )
            Spacer(Modifier.width(18.dp))
        }
        Text(
            "RÉGLAGES",
            color = colors.muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.4.sp,
            modifier = Modifier.clickable(onClick = onOpenSettings).padding(vertical = 10.dp, horizontal = 4.dp),
        )
    }
}

@Composable
private fun SettingsScreen(
    progress: DayProgress,
    onMoveStart: (Int) -> Unit,
    onMoveEnd: (Int) -> Unit,
    showSeconds: Boolean,
    onShowSecondsChange: (Boolean) -> Unit,
    soundSettings: SoundSettings,
    onSoundSettingsChange: (SoundSettings) -> Unit,
    onPreviewSound: (SoundCue) -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.radialGradient(
                colors = listOf(colors.glow, colors.ink),
                radius = 950f,
            ),
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "‹  AUJOURD’HUI",
                    color = colors.muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.clickable(onClick = onBack).padding(vertical = 10.dp, horizontal = 4.dp),
                )
                Spacer(Modifier.weight(1f))
                Text("RÉGLAGES", color = colors.cloud, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.2.sp)
            }

            Spacer(Modifier.height(48.dp))
            Column(modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp)) {
                Text("JOURNÉE", color = colors.mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Définissez la plage utilisée pour représenter le temps disponible chaque jour.",
                    color = colors.muted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
                Spacer(Modifier.height(18.dp))
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
                Spacer(Modifier.height(24.dp))
                Text("AFFICHAGE", color = colors.mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(colors.panel, RoundedCornerShape(18.dp))
                        .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
                        .clickable { onShowSecondsChange(!showSeconds) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "AFFICHER LES SECONDES",
                            color = colors.cloud,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.1.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Désactivez-les pour un affichage plus calme et moins de rafraîchissements.",
                            color = colors.muted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Switch(
                        checked = showSeconds,
                        onCheckedChange = onShowSecondsChange,
                    )
                }
                Spacer(Modifier.height(24.dp))
                SoundSettingsPanel(
                    settings = soundSettings,
                    onSettingsChange = onSoundSettingsChange,
                    onPreview = onPreviewSound,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Les changements sont enregistrés automatiquement et s’appliquent à tous les jours.",
                    color = colors.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun SoundSettingsPanel(
    settings: SoundSettings,
    onSettingsChange: (SoundSettings) -> Unit,
    onPreview: (SoundCue) -> Unit,
) {
    val colors = LocalDayViewColors.current
    Text("SONS", color = colors.mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
    Spacer(Modifier.height(8.dp))
    Text(
        "Des repères doux pour sentir le temps sans surveiller l’écran.",
        color = colors.muted,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    )
    Spacer(Modifier.height(14.dp))
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(colors.panel, RoundedCornerShape(18.dp))
            .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
            .clickable { onSettingsChange(settings.copy(enabled = !settings.enabled)) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("REPÈRES SONORES", color = colors.cloud, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            Spacer(Modifier.height(4.dp))
            Text("Désactivés par défaut.", color = colors.muted, fontSize = 11.sp)
        }
        Switch(checked = settings.enabled, onCheckedChange = { onSettingsChange(settings.copy(enabled = it)) })
    }

    if (settings.enabled) {
        Spacer(Modifier.height(10.dp))
        Column(
            modifier = Modifier.fillMaxWidth()
                .background(colors.panel, RoundedCornerShape(18.dp))
                .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            SoundCueSettingRow(
                label = "DÉBUT DE JOURNÉE",
                detail = "Bol clair",
                checked = settings.startCueEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(startCueEnabled = it)) },
                onPreview = { onPreview(SoundCue.DAY_START) },
            )
            SettingsDivider()
            SoundCueSettingRow(
                label = "REPÈRE INTERMÉDIAIRE",
                detail = "Tintement léger",
                checked = settings.intervalCueEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(intervalCueEnabled = it)) },
                onPreview = { onPreview(SoundCue.INTERVAL) },
            )
            if (settings.intervalCueEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("TOUTES LES", color = colors.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(Modifier.weight(1f))
                    TimeButton("−", enabled = settings.intervalMinutes > 30) {
                        onSettingsChange(settings.copy(intervalMinutes = settings.intervalMinutes - 30))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("${settings.intervalMinutes} min", color = colors.cloud, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(10.dp))
                    TimeButton("+", enabled = settings.intervalMinutes < 180) {
                        onSettingsChange(settings.copy(intervalMinutes = settings.intervalMinutes + 30))
                    }
                }
            }
            SettingsDivider()
            SoundCueSettingRow(
                label = "FIN DE JOURNÉE",
                detail = "Gong grave",
                checked = settings.endCueEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(endCueEnabled = it)) },
                onPreview = { onPreview(SoundCue.DAY_END) },
            )
            SettingsDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("VOLUME", color = colors.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Spacer(Modifier.height(3.dp))
                    Text("${settings.volumePercent} %", color = colors.cloud, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.weight(1f))
                TimeButton("−", enabled = settings.volumePercent > 10) {
                    onSettingsChange(settings.copy(volumePercent = settings.volumePercent - 10))
                }
                Spacer(Modifier.width(8.dp))
                TimeButton("+", enabled = settings.volumePercent < 100) {
                    onSettingsChange(settings.copy(volumePercent = settings.volumePercent + 10))
                }
            }
        }
    }
}

@Composable
private fun SoundCueSettingRow(
    label: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onPreview: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = colors.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            Spacer(Modifier.height(3.dp))
            Text(detail, color = colors.cloud, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        PreviewSoundButton(onPreview)
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PreviewSoundButton(onClick: () -> Unit) {
    val colors = LocalDayViewColors.current
    Box(
        modifier = Modifier.background(colors.mint.copy(alpha = .12f), RoundedCornerShape(9.dp))
            .border(1.dp, colors.mint.copy(alpha = .25f), RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("ÉCOUTER", color = colors.mint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
    }
}

@Composable
private fun SettingsDivider() {
    val colors = LocalDayViewColors.current
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.overlay.copy(alpha = .06f)))
}

@Composable
private fun CountdownCircle(
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
                    PomodoroStatus.FINISHED -> "SLOT TERMINÉ"
                },
                color = if (progress.status == PomodoroStatus.FINISHED) colors.mint else colors.muted,
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
        } else if (progress.status == PomodoroStatus.FINISHED) {
            Text(
                "VOTRE INTENTION",
                color = colors.muted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                intention.ifBlank { "Une seule chose à la fois." },
                color = colors.cloud,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "COMMENT S’EST PASSÉ CE FOCUS ?",
                color = colors.muted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = .9.sp,
            )
            Spacer(Modifier.height(9.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                FocusActionButton(
                    "TERMINÉ",
                    colors.mint,
                    modifier = Modifier.weight(1f),
                    filled = true,
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
                    colors.mint,
                    modifier = Modifier.weight(1.25f),
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
                placeholder = "Ex. terminé le plan de la présentation",
                onValueChange = onIntentionChange,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                TimeButton("−", enabled = progress.durationMinutes > 5) { onDurationChange(-5) }
                Spacer(Modifier.width(18.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(progress.durationMinutes.toString(), color = colors.cloud, fontSize = 28.sp, fontWeight = FontWeight.Light)
                    Text("MINUTES", color = colors.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                Spacer(Modifier.width(18.dp))
                TimeButton("+", enabled = progress.durationMinutes < 180) { onDurationChange(5) }
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
        modifier = modifier.background(backgroundColor, RoundedCornerShape(12.dp))
            .border(1.dp, color.copy(alpha = if (enabled) .38f else .1f), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = contentColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = .8.sp,
            maxLines = 1,
            softWrap = false,
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
                        placeholder = "Que voulez-vous accomplir ?",
                        onValueChange = onTitleChange,
                        modifier = Modifier.weight(1f),
                    )
                    GoalTextField(
                        value = deadlineText,
                        placeholder = GOAL_DATE_PLACEHOLDER,
                        onValueChange = onDeadlineChange,
                        isError = !deadlineIsValid,
                        modifier = Modifier.width(148.dp),
                    )
                }
            } else {
                Column {
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
    placeholder: String,
    onValueChange: (String) -> Unit,
    isError: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDayViewColors.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = colors.cloud, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        cursorBrush = Brush.verticalGradient(listOf(colors.mint, colors.mint)),
        modifier = modifier.fillMaxWidth()
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
