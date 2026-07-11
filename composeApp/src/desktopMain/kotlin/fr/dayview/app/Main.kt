package fr.dayview.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.dayview_tray
import fr.dayview.app.generated.resources.dayview_tray_monochrome
import kotlin.math.ceil
import kotlin.time.Clock

fun main() = application {
    val preferences = remember { DesktopDayPreferences() }
    val focusStatusItem = remember { MacFocusStatusItem() }
    val frontmostApplicationProvider = remember { MacFrontmostApplicationProvider() }
    val focusDriftDetector = remember { FocusDriftDetector() }
    val focusResumeDetector = remember { FocusResumeDetector() }
    val soundCuePlayer = remember { createSoundCuePlayer() }
    val soundAlertScheduler = remember { SoundAlertScheduler() }
    val breakReminderScheduler = remember { BreakReminderScheduler() }
    var isWindowVisible by remember { mutableStateOf(true) }
    var isMiniWindowVisible by remember { mutableStateOf(false) }
    var focusDriftReminderId by remember { mutableStateOf<Long?>(null) }
    var focusResumeRitualId by remember { mutableStateOf<Long?>(null) }
    var nowMillis by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    var startMinutes by remember { mutableStateOf(preferences.loadStartMinutes()) }
    var endMinutes by remember { mutableStateOf(preferences.loadEndMinutes()) }
    var goalTitle by remember { mutableStateOf(preferences.loadGoalTitle()) }
    var goalDeadline by remember { mutableStateOf(preferences.loadGoalDeadlineMillis()) }
    var pomodoroMinutes by remember { mutableStateOf(preferences.loadPomodoroMinutes()) }
    var pomodoroEnd by remember { mutableStateOf(preferences.loadPomodoroEndMillis()) }
    var focusIntention by remember { mutableStateOf(preferences.loadFocusIntention()) }
    var showSeconds by remember { mutableStateOf(preferences.loadShowSeconds()) }
    var monochromeMenuBarIcon by remember { mutableStateOf(preferences.loadMonochromeMenuBarIcon()) }

    LaunchedEffect(Unit) {
        while (true) {
            val currentNowMillis = Clock.System.now().toEpochMilliseconds()
            nowMillis = currentNowMillis
            val currentStartMinutes = preferences.loadStartMinutes()
            val currentEndMinutes = preferences.loadEndMinutes()
            startMinutes = currentStartMinutes
            endMinutes = currentEndMinutes
            goalTitle = preferences.loadGoalTitle()
            goalDeadline = preferences.loadGoalDeadlineMillis()
            pomodoroMinutes = preferences.loadPomodoroMinutes()
            val currentPomodoroEnd = preferences.loadPomodoroEndMillis()
            pomodoroEnd = currentPomodoroEnd
            focusIntention = preferences.loadFocusIntention()
            showSeconds = preferences.loadShowSeconds()

            val focusIsActive = currentPomodoroEnd != null && currentPomodoroEnd > currentNowMillis
            val soundSettings = preferences.loadSoundSettings()
            val soundCue = soundAlertScheduler.observe(
                nowMillis = currentNowMillis,
                startMinutesOfDay = currentStartMinutes,
                endMinutesOfDay = currentEndMinutes,
                intervalMinutes = soundSettings.intervalMinutes,
            )
            if (soundCue != null && soundSettings.allowsDayCue(soundCue, focusIsActive)) {
                soundCuePlayer.play(soundCue, soundSettings.volumePercent / 100f)
            }
            val breakStartMillis = currentPomodoroEnd?.takeIf { it <= currentNowMillis }
            if (breakReminderScheduler.observe(currentNowMillis, breakStartMillis)) {
                soundCuePlayer.play(SoundCue.BREAK_REMINDER, soundSettings.volumePercent / 100f)
            }

            val shouldShowResumeRitual = focusResumeDetector.observe(focusIsActive, currentNowMillis)
            val frontmostBundleId = if (focusIsActive && !shouldShowResumeRitual) {
                frontmostApplicationProvider.bundleIdentifier()
            } else {
                null
            }
            if (shouldShowResumeRitual) {
                focusResumeRitualId = currentNowMillis
                focusDriftReminderId = null
                isMiniWindowVisible = false
                isWindowVisible = true
            } else if (focusDriftDetector.observe(focusIsActive, currentNowMillis, frontmostBundleId)) {
                focusDriftReminderId = currentNowMillis
                isMiniWindowVisible = false
                isWindowVisible = true
            } else if (!focusIsActive) {
                focusDriftReminderId = null
                focusResumeRitualId = null
            }
            delay(1_000)
        }
    }

    val dayProgress = calculateDayProgress(nowMillis, startMinutes, endMinutes)
    val dayStatus = if (dayProgress.isFinished) {
        "Journée terminée"
    } else {
        "Aujourd’hui · ${dayProgress.remainingHours} h ${dayProgress.remainingMinutes.toString().padStart(2, '0')}"
    }
    val goalStatus = goalDeadline?.let { deadline ->
        val workingMillis = calculateGoalWorkingMillis(nowMillis, deadline, startMinutes, endMinutes)
        val hours = ceil(workingMillis / 3_600_000.0).toLong()
        when {
            deadline <= nowMillis -> "Objectif · échéance atteinte"
            goalTitle.isBlank() -> "Objectif · $hours h"
            else -> "${goalTitle.take(24)} · $hours h"
        }
    }
    val pomodoro = calculatePomodoroProgress(nowMillis, pomodoroMinutes, pomodoroEnd)
    val focusStatus = when (pomodoro.status) {
        PomodoroStatus.ACTIVE -> listOfNotNull(
            "Focus",
            focusIntention.take(24).takeIf(String::isNotBlank),
            formatPomodoroClock(pomodoro),
        ).joinToString(" · ")
        PomodoroStatus.BREAK -> "Pause · ${formatBreakClock(pomodoro)}"
        PomodoroStatus.IDLE -> null
    }

    LaunchedEffect(pomodoro.status, pomodoro.remainingMillis / 1_000) {
        focusStatusItem.update(
            when (pomodoro.status) {
                PomodoroStatus.ACTIVE -> formatPomodoroCompactMinutes(pomodoro)
                PomodoroStatus.BREAK -> "P ${pomodoro.breakElapsedMillis / 60_000L}m"
                PomodoroStatus.IDLE -> null
            },
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            focusStatusItem.close()
            soundCuePlayer.close()
        }
    }

    Tray(
        icon = painterResource(
            if (monochromeMenuBarIcon) Res.drawable.dayview_tray_monochrome else Res.drawable.dayview_tray,
        ),
        tooltip = dayStatus,
        onAction = {
            if (!isMiniWindowVisible) isWindowVisible = true
        },
    ) {
        focusStatus?.let { Item(it, enabled = false, onClick = {}) }
        Item(dayStatus, enabled = false, onClick = {})
        goalStatus?.let { Item(it, enabled = false, onClick = {}) }
        Separator()
        Item(if (isMiniWindowVisible) "Ouvrir la fenêtre complète" else "Afficher la mini-fenêtre") {
            isMiniWindowVisible = !isMiniWindowVisible
            isWindowVisible = !isMiniWindowVisible
        }
        Item(if (isWindowVisible) "Masquer DayView" else "Ouvrir DayView") {
            if (isWindowVisible) {
                isWindowVisible = false
            } else {
                isMiniWindowVisible = false
                isWindowVisible = true
            }
        }
        Item("Quitter DayView", onClick = ::exitApplication)
    }

    if (isWindowVisible) {
        Window(
            onCloseRequest = { isWindowVisible = false },
            title = "DayView",
        ) {
            window.minimumSize = java.awt.Dimension(420, 680)
            LaunchedEffect(focusDriftReminderId, focusResumeRitualId) {
                if (focusDriftReminderId != null || focusResumeRitualId != null) {
                    window.toFront()
                    window.requestFocus()
                }
            }
            DayViewApp(
                preferences = preferences,
                monochromeMenuBarIcon = monochromeMenuBarIcon,
                onMonochromeMenuBarIconChange = { monochrome ->
                    monochromeMenuBarIcon = monochrome
                    preferences.saveMonochromeMenuBarIcon(monochrome)
                },
                onOpenMiniWindow = {
                    isWindowVisible = false
                    isMiniWindowVisible = true
                },
                showFocusDriftReminder = focusDriftReminderId != null,
                onDismissFocusDriftReminder = { focusDriftReminderId = null },
                showFocusResumeRitual = focusResumeRitualId != null,
                onDismissFocusResumeRitual = { focusResumeRitualId = null },
                scheduleSoundAlerts = false,
            )
        }
    }

    if (isMiniWindowVisible) {
        Window(
            onCloseRequest = { isMiniWindowVisible = false },
            title = "DayView Mini",
            state = rememberWindowState(width = 360.dp, height = 520.dp),
            alwaysOnTop = true,
            resizable = false,
        ) {
            DayViewMiniApp(
                progress = dayProgress,
                showSeconds = showSeconds,
                nowMillis = nowMillis,
                goalTitle = goalTitle,
                goalDeadlineMillis = goalDeadline,
                pomodoro = pomodoro,
                focusIntention = focusIntention,
            )
        }
    }
}
