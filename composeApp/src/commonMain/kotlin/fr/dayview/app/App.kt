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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.time.Clock

@Composable
fun DayViewApp(
    preferences: DayPreferences = DefaultDayPreferences,
    monochromeMenuBarIcon: Boolean? = null,
    onMonochromeMenuBarIconChange: ((Boolean) -> Unit)? = null,
    launchAtLogin: Boolean? = null,
    onLaunchAtLoginChange: ((Boolean) -> Unit)? = null,
    onOpenMiniWindow: (() -> Unit)? = null,
    onFocusAlarmChange: (endMillis: Long?, intention: String) -> Unit = { _, _ -> },
    showFocusDriftReminder: Boolean = false,
    onDismissFocusDriftReminder: () -> Unit = {},
    showFocusResumeRitual: Boolean = false,
    onDismissFocusResumeRitual: () -> Unit = {},
    scheduleSoundAlerts: Boolean = true,
) {
    DayViewTheme { colors ->
        Surface(modifier = Modifier.fillMaxSize(), color = colors.ink) {
                val controller = remember(preferences) { DayViewController(preferences) }
                var state by remember(controller) { mutableStateOf(controller.state) }
                val soundPlayer = remember { createSoundCuePlayer() }
                val soundScheduler = remember { SoundAlertScheduler() }

                PlatformBackHandler(enabled = state.destination == DayViewDestination.SETTINGS) {
                    state = controller.openToday()
                }
                DisposableEffect(soundPlayer) {
                    onDispose { soundPlayer.close() }
                }
                LaunchedEffect(state.showSeconds, state.pomodoroEndMillis) {
                    while (true) {
                        val nowMillis = Clock.System.now().toEpochMilliseconds()
                        state = controller.tick(nowMillis)
                        val refreshDelay = if (state.showSeconds || state.pomodoroEndMillis != null) {
                            1_000L
                        } else {
                            60_000L - nowMillis % 60_000L
                        }
                        delay(refreshDelay)
                    }
                }
                LaunchedEffect(
                    state.nowMillis,
                    state.startMinutes,
                    state.endMinutes,
                    state.soundSettings,
                    scheduleSoundAlerts,
                    state.focusIsActive,
                ) {
                    if (scheduleSoundAlerts) {
                        val cue = soundScheduler.observe(
                            nowMillis = state.nowMillis,
                            startMinutesOfDay = state.startMinutes,
                            endMinutesOfDay = state.endMinutes,
                            intervalMinutes = state.soundSettings.intervalMinutes,
                        )
                        if (cue != null && state.soundSettings.allowsDayCue(cue, state.focusIsActive)) {
                            soundPlayer.play(cue, state.soundSettings.volumePercent / 100f)
                        }
                    }
                }

                if (state.destination == DayViewDestination.SETTINGS) {
                    SettingsScreen(
                        state = state,
                        platformState = SettingsPlatformUiState(
                            monochromeMenuBarIcon = monochromeMenuBarIcon,
                            launchAtLogin = launchAtLogin,
                        ),
                        actions = SettingsScreenActions(
                            moveStart = { state = controller.moveStart(it) },
                            moveEnd = { state = controller.moveEnd(it) },
                            changeShowSeconds = { state = controller.setShowSeconds(it) },
                            changeMonochromeMenuBarIcon = onMonochromeMenuBarIconChange,
                            changeLaunchAtLogin = onLaunchAtLoginChange,
                            changeSoundSettings = { state = controller.setSoundSettings(it) },
                            previewSound = { cue ->
                                soundPlayer.play(cue, state.soundSettings.volumePercent / 100f)
                            },
                            back = { state = controller.openToday() },
                        ),
                    )
                } else {
                    DayViewScreen(
                        state = state,
                        actions = DayViewScreenActions(
                            openSettings = { state = controller.openSettings() },
                            openMiniWindow = onOpenMiniWindow,
                            changeGoalTitle = { state = controller.setGoalTitle(it) },
                            changeGoalDeadline = { state = controller.setGoalDeadlineText(it) },
                            changeFocusIntention = { state = controller.setFocusIntention(it) },
                            changePomodoroDuration = { state = controller.changePomodoroDuration(it) },
                            startPomodoro = {
                                state = controller.startPomodoro()
                                state.pomodoroEndMillis?.let { onFocusAlarmChange(it, state.focusIntention) }
                            },
                            stopPomodoro = {
                                val intention = state.focusIntention
                                state = controller.stopPomodoro()
                                onFocusAlarmChange(null, intention)
                            },
                            closePomodoro = { outcome ->
                                val intention = state.focusIntention
                                state = controller.closePomodoro(outcome)
                                onFocusAlarmChange(null, intention)
                            },
                        ),
                        reminders = FocusReminderUiState(
                            showDriftReminder = showFocusDriftReminder,
                            dismissDriftReminder = onDismissFocusDriftReminder,
                            showResumeRitual = showFocusResumeRitual,
                            dismissResumeRitual = onDismissFocusResumeRitual,
                        ),
                    )
                }
        }
    }
}
