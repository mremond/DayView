package fr.dayview.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.painterResource
import kotlin.math.ceil
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.Instant

fun main() = application {
    val preferences = remember { desktopDayPreferences() }
    val scope = rememberCoroutineScope()
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
    var focusDriftReminderId by remember { mutableStateOf<Instant?>(null) }
    var focusResumeRitualId by remember { mutableStateOf<Instant?>(null) }
    var now by remember { mutableStateOf(Clock.System.now()) }
    var preferenceSnapshot by remember { mutableStateOf(runBlocking { preferences.snapshots.first() }) }
    var monochromeMenuBarIcon by remember { mutableStateOf(runBlocking { preferences.loadMonochromeMenuBarIcon() }) }
    var launchAtLogin by remember { mutableStateOf(loginLauncher.isEnabled()) }
    val initialFocusPresence = remember { runBlocking { preferences.loadFocusPresence() } }
    val presenceAccumulator = remember {
        PresenceAccumulator().also {
            val (day, intervals) = initialFocusPresence
            if (day >= 0) it.restore(intervals, day)
        }
    }
    var focusPresenceIntervals by remember { mutableStateOf(initialFocusPresence.second) }
    var lastPresenceSave by remember { mutableStateOf(Instant.DISTANT_PAST) }
    var wasFocusActive by remember { mutableStateOf(false) }

    LaunchedEffect(preferences) {
        preferences.snapshots.collect { preferenceSnapshot = it }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val currentNow = Clock.System.now()
            now = currentNow
            val currentPreferences = preferenceSnapshot
            val currentPomodoroEnd = currentPreferences.pomodoroEnd

            val focusIsActive = currentPomodoroEnd != null && currentPomodoroEnd > currentNow
            val soundSettings = currentPreferences.soundSettings
            val soundCue = soundAlertScheduler.observe(
                now = currentNow,
                startMinutesOfDay = currentPreferences.startMinutes,
                endMinutesOfDay = currentPreferences.endMinutes,
                intervalMinutes = soundSettings.intervalMinutes,
            )
            if (soundCue != null && soundSettings.allowsDayCue(soundCue, focusIsActive)) {
                soundCuePlayer.play(soundCue, soundSettings.volumePercent / 100f)
            }
            val breakStart = currentPomodoroEnd?.takeIf { it <= currentNow }
            if (breakReminderScheduler.observe(currentNow, breakStart)) {
                soundCuePlayer.play(SoundCue.BREAK_REMINDER, soundSettings.volumePercent / 100f)
            }

            val shouldShowResumeRitual = focusResumeDetector.observe(focusIsActive, currentNow)
            val frontmostBundleId = if (focusIsActive && !shouldShowResumeRitual) {
                frontmostApplicationProvider.bundleIdentifier()
            } else {
                null
            }
            val onGoalBundleIds = currentPreferences.onGoalApps.map { it.bundleId }.toSet()
            if (shouldShowResumeRitual) {
                focusResumeRitualId = currentNow
                focusDriftReminderId = null
                isMiniWindowVisible = false
                isWindowVisible = true
            } else if (
                focusDriftDetector.observe(
                    focusIsActive,
                    currentNow,
                    frontmostBundleId,
                    onGoalBundleIds,
                )
            ) {
                focusDriftReminderId = currentNow
                nudgeNotifier.notify(currentPreferences.focusIntention)
            } else if (!focusIsActive) {
                focusDriftReminderId = null
                focusResumeRitualId = null
            }

            val dayKey = currentNow
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .date.toEpochDays()
            val classification = classifyFrontmost(frontmostBundleId, onGoalBundleIds)
            val updatedIntervals = when {
                focusIsActive -> presenceAccumulator.observe(currentNow, classification, dayKey)
                wasFocusActive -> presenceAccumulator.endSession() // session just ended: close the run
                else -> focusPresenceIntervals
            }
            if (updatedIntervals != focusPresenceIntervals) {
                val structuralChange = updatedIntervals.size != focusPresenceIntervals.size
                focusPresenceIntervals = updatedIntervals
                if (structuralChange || currentNow - lastPresenceSave >= 30.seconds) {
                    preferences.saveFocusPresence(dayKey, updatedIntervals)
                    lastPresenceSave = currentNow
                }
            }
            wasFocusActive = focusIsActive
            delay(1_000)
        }
    }

    val startMinutes = preferenceSnapshot.startMinutes
    val endMinutes = preferenceSnapshot.endMinutes
    val goalTitle = preferenceSnapshot.goalTitle
    val goalDeadline = preferenceSnapshot.goalDeadline
    val pomodoroMinutes = preferenceSnapshot.pomodoroMinutes
    val pomodoroEnd = preferenceSnapshot.pomodoroEnd
    val focusIntention = preferenceSnapshot.focusIntention
    val showSeconds = preferenceSnapshot.showSeconds
    val dayProgress = calculateDayProgress(now, startMinutes, endMinutes)
    val dayStatus = if (dayProgress.isFinished) {
        "Journée terminée"
    } else {
        "Aujourd’hui · ${dayProgress.remainingHours} h ${dayProgress.remainingMinutes.toString().padStart(2, '0')}"
    }
    val goalStatus = goalDeadline?.let { deadline ->
        val working = calculateGoalWorkingTime(now, deadline, startMinutes, endMinutes)
        val hours = ceil(working.toDouble(DurationUnit.HOURS)).toLong()
        when {
            deadline <= now -> "Objectif · échéance atteinte"
            goalTitle.isBlank() -> "Objectif · $hours h"
            else -> "${goalTitle.take(24)} · $hours h"
        }
    }
    val pomodoro = calculatePomodoroProgress(now, pomodoroMinutes, pomodoroEnd)
    val focusStatus = when (pomodoro.status) {
        PomodoroStatus.ACTIVE -> listOfNotNull(
            "Focus",
            focusIntention.take(24).takeIf(String::isNotBlank),
            formatPomodoroClock(pomodoro),
        ).joinToString(" · ")
        PomodoroStatus.BREAK -> "Pause · ${formatBreakClock(pomodoro)}"
        PomodoroStatus.IDLE -> null
    }

    LaunchedEffect(pomodoro.status, pomodoro.remaining.inWholeSeconds) {
        focusStatusItem.update(
            when (pomodoro.status) {
                PomodoroStatus.ACTIVE -> formatPomodoroCompactMinutes(pomodoro)
                PomodoroStatus.BREAK -> "P ${pomodoro.breakElapsed.inWholeMinutes}m"
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
                    scope.launch { preferences.saveMonochromeMenuBarIcon(monochrome) }
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
                focusPresenceIntervals = focusPresenceIntervals,
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
                now = now,
                goalTitle = goalTitle,
                goalDeadline = goalDeadline,
                pomodoro = pomodoro,
                focusIntention = focusIntention,
                onStartFocus = { intention ->
                    scope.launch {
                        val s = preferences.snapshots.first()
                        preferences.persist(
                            s.copy(
                                focusIntention = intention.trim().take(100),
                                pomodoroMinutes = pomodoroMinutes,
                                pomodoroEnd = focusStartEnd(now, pomodoroMinutes),
                            ),
                        )
                    }
                },
                onStopFocus = {
                    scope.launch {
                        val s = preferences.snapshots.first()
                        preferences.persist(s.copy(pomodoroMinutes = pomodoroMinutes, pomodoroEnd = null))
                    }
                },
            )
        }
    }
}
