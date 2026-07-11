package fr.dayview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    DayViewTheme { colors ->
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
                if (pomodoro.status != PomodoroStatus.IDLE) {
                    Spacer(Modifier.height(10.dp))
                    MiniFocus(pomodoro, focusIntention)
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
                if (isBreak) "PAUSE EN COURS" else "FOCUS EN COURS",
                color = if (isBreak) colors.mint else colors.amber,
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
            if (isBreak) formatBreakClock(progress) else formatPomodoroClock(progress),
            color = colors.cloud,
            fontSize = 24.sp,
            fontWeight = FontWeight.Light,
        )
    }
}
