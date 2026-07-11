package fr.dayview.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.dayview_tray
import fr.dayview.app.generated.resources.dayview_tray_monochrome
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import kotlin.math.ceil
import kotlin.time.Clock

fun main() = application {
    val preferences = remember { DesktopDayPreferences() }
    val loginLauncher = remember { MacLoginLauncher() }
    val focusStatusItem = remember { MacFocusStatusItem() }
    val frontmostApplicationProvider = remember { MacFrontmostApplicationProvider() }
    val runningApplicationsProvider = remember { MacRunningApplicationsProvider() }
    val focusDriftDetector = remember { FocusDriftDetector() }
    val focusResumeDetector = remember { FocusResumeDetector() }
    val nudgeNotifier = remember { MacFocusNudgeNotifier() }
    val soundCuePlayer = remember { createSoundCuePlayer() }
    val soundAlertScheduler = remember { SoundAlertScheduler() }
    val breakReminderScheduler = remember { BreakReminderScheduler() }
    var isWindowVisible by remember { mutableStateOf(true) }
    var isMiniWindowVisible by remember { mutableStateOf(false) }
    var focusDriftReminderId by remember { mutableStateOf<Long?>(null) }
    var focusResumeRitualId by remember { mutableStateOf<Long?>(null) }
    var nowMillis by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    var preferenceSnapshot by remember { mutableStateOf(preferences.snapshot()) }
    var monochromeMenuBarIcon by remember { mutableStateOf(preferences.loadMonochromeMenuBarIcon()) }
    var launchAtLogin by remember { mutableStateOf(loginLauncher.isEnabled()) }

    DisposableEffect(preferences) {
        val stopObserving = preferences.observe { preferenceSnapshot = it }
        onDispose(stopObserving)
    }

    LaunchedEffect(Unit) {
        while (true) {
            val currentNowMillis = Clock.System.now().toEpochMilliseconds()
            nowMillis = currentNowMillis
            val currentPreferences = preferenceSnapshot
            val currentPomodoroEnd = currentPreferences.pomodoroEndMillis

            val focusIsActive = currentPomodoroEnd != null && currentPomodoroEnd > currentNowMillis
            val soundSettings = currentPreferences.soundSettings
            val soundCue = soundAlertScheduler.observe(
                nowMillis = currentNowMillis,
                startMinutesOfDay = currentPreferences.startMinutes,
                endMinutesOfDay = currentPreferences.endMinutes,
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
            } else if (
                focusDriftDetector.observe(
                    focusIsActive,
                    currentNowMillis,
                    frontmostBundleId,
                    currentPreferences.onGoalApps.map { it.bundleId }.toSet(),
                )
            ) {
                focusDriftReminderId = currentNowMillis
                nudgeNotifier.notify(currentPreferences.focusIntention)
            } else if (!focusIsActive) {
                focusDriftReminderId = null
                focusResumeRitualId = null
            }
            delay(1_000)
        }
    }

    val startMinutes = preferenceSnapshot.startMinutes
    val endMinutes = preferenceSnapshot.endMinutes
    val goalTitle = preferenceSnapshot.goalTitle
    val goalDeadline = preferenceSnapshot.goalDeadlineMillis
    val pomodoroMinutes = preferenceSnapshot.pomodoroMinutes
    val pomodoroEnd = preferenceSnapshot.pomodoroEndMillis
    val focusIntention = preferenceSnapshot.focusIntention
    val showSeconds = preferenceSnapshot.showSeconds
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
            LaunchedEffect(focusResumeRitualId) {
                if (focusResumeRitualId != null) {
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
                launchAtLogin = launchAtLogin.takeIf { loginLauncher.isAvailable() },
                onLaunchAtLoginChange = { enabled ->
                    if (loginLauncher.setEnabled(enabled)) launchAtLogin = enabled
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
                runningApps = { runningApplicationsProvider.runningApps() },
            )
        }
    }

    if (isMiniWindowVisible) {
        Window(
            onCloseRequest = { isMiniWindowVisible = false },
            title = "DayView Mini",
            state = rememberWindowState(width = 360.dp, height = 520.dp),
            alwaysOnTop = true,
            resizable = true,
        ) {
            window.minimumSize = java.awt.Dimension(300, 400)
            DayViewMiniApp(
                progress = dayProgress,
                showSeconds = showSeconds,
                nowMillis = nowMillis,
                goalTitle = goalTitle,
                goalDeadlineMillis = goalDeadline,
                pomodoro = pomodoro,
                focusIntention = focusIntention,
                onStartFocus = { intention ->
                    preferences.saveFocusIntention(intention.trim().take(100))
                    preferences.savePomodoro(
                        pomodoroMinutes,
                        focusStartEndMillis(nowMillis, pomodoroMinutes),
                    )
                },
                onStopFocus = { preferences.savePomodoro(pomodoroMinutes, null) },
            )
        }
    }
}
