package fr.dayview.app

import androidx.compose.runtime.LaunchedEffect
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
    var isWindowVisible by remember { mutableStateOf(true) }
    var nowMillis by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    var startMinutes by remember { mutableStateOf(preferences.loadStartMinutes()) }
    var endMinutes by remember { mutableStateOf(preferences.loadEndMinutes()) }
    var goalTitle by remember { mutableStateOf(preferences.loadGoalTitle()) }
    var goalDeadline by remember { mutableStateOf(preferences.loadGoalDeadlineMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = Clock.System.now().toEpochMilliseconds()
            startMinutes = preferences.loadStartMinutes()
            endMinutes = preferences.loadEndMinutes()
            goalTitle = preferences.loadGoalTitle()
            goalDeadline = preferences.loadGoalDeadlineMillis()
            delay(30_000)
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

    Tray(
        icon = painterResource(Res.drawable.dayview_tray),
        tooltip = dayStatus,
        onAction = { isWindowVisible = true },
    ) {
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
            DayViewApp(preferences)
        }
    }
}
