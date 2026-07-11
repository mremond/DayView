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
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.dayview_tray
import kotlin.math.ceil
import kotlin.time.Clock

fun main() = application {
    val preferences = remember { DesktopDayPreferences() }
    val focusStatusItem = remember { MacFocusStatusItem() }
    val frontmostApplicationProvider = remember { MacFrontmostApplicationProvider() }
    val focusDriftDetector = remember { FocusDriftDetector() }
    val focusResumeDetector = remember { FocusResumeDetector() }
    var isWindowVisible by remember { mutableStateOf(true) }
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

    LaunchedEffect(Unit) {
        while (true) {
            val currentNowMillis = Clock.System.now().toEpochMilliseconds()
            nowMillis = currentNowMillis
            startMinutes = preferences.loadStartMinutes()
            endMinutes = preferences.loadEndMinutes()
            goalTitle = preferences.loadGoalTitle()
            goalDeadline = preferences.loadGoalDeadlineMillis()
            pomodoroMinutes = preferences.loadPomodoroMinutes()
            val currentPomodoroEnd = preferences.loadPomodoroEndMillis()
            pomodoroEnd = currentPomodoroEnd
            focusIntention = preferences.loadFocusIntention()

            val focusIsActive = currentPomodoroEnd != null && currentPomodoroEnd > currentNowMillis
            val shouldShowResumeRitual = focusResumeDetector.observe(focusIsActive, currentNowMillis)
            val frontmostBundleId = if (focusIsActive && !shouldShowResumeRitual) {
                frontmostApplicationProvider.bundleIdentifier()
            } else {
                null
            }
            if (shouldShowResumeRitual) {
                focusResumeRitualId = currentNowMillis
                focusDriftReminderId = null
                isWindowVisible = true
            } else if (focusDriftDetector.observe(focusIsActive, currentNowMillis, frontmostBundleId)) {
                focusDriftReminderId = currentNowMillis
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
        PomodoroStatus.FINISHED -> "Focus · slot terminé"
        PomodoroStatus.IDLE -> null
    }

    LaunchedEffect(pomodoro.status, pomodoro.remainingMillis / 1_000) {
        focusStatusItem.update(
            if (pomodoro.status == PomodoroStatus.ACTIVE) formatPomodoroCompactMinutes(pomodoro) else null,
        )
    }
    DisposableEffect(Unit) {
        onDispose { focusStatusItem.close() }
    }

    Tray(
        icon = painterResource(Res.drawable.dayview_tray),
        tooltip = dayStatus,
        onAction = { isWindowVisible = true },
    ) {
        focusStatus?.let { Item(it, enabled = false, onClick = {}) }
        Item(dayStatus, enabled = false, onClick = {})
        goalStatus?.let { Item(it, enabled = false, onClick = {}) }
        Separator()
        Item(if (isWindowVisible) "Masquer DayView" else "Ouvrir DayView") {
            isWindowVisible = !isWindowVisible
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
                showFocusDriftReminder = focusDriftReminderId != null,
                onDismissFocusDriftReminder = { focusDriftReminderId = null },
                showFocusResumeRitual = focusResumeRitualId != null,
                onDismissFocusResumeRitual = { focusResumeRitualId = null },
            )
        }
    }
}
