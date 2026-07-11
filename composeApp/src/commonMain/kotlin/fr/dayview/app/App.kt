package fr.dayview.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
            val state = controller.state
            val soundPlayer = remember { createSoundCuePlayer() }
            val soundScheduler = remember { SoundAlertScheduler() }

            DisposableEffect(controller, preferences) {
                val stopObserving = preferences.observe { controller.onPreferencesChanged(it) }
                onDispose(stopObserving)
            }
            PlatformBackHandler(enabled = state.destination == DayViewDestination.SETTINGS) {
                controller.openToday()
            }
            DisposableEffect(soundPlayer) {
                onDispose { soundPlayer.close() }
            }
            LaunchedEffect(state.showSeconds, state.pomodoroEndMillis) {
                while (true) {
                    val nowMillis = Clock.System.now().toEpochMilliseconds()
                    controller.tick(nowMillis)
                    val current = controller.state
                    val refreshDelay = if (current.showSeconds || current.pomodoroEndMillis != null) {
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
                        changeStartTime = { controller.setStartMinutes(it) },
                        changeEndTime = { controller.setEndMinutes(it) },
                        changeShowSeconds = { controller.setShowSeconds(it) },
                        changeMonochromeMenuBarIcon = onMonochromeMenuBarIconChange,
                        changeLaunchAtLogin = onLaunchAtLoginChange,
                        changeSoundSettings = { controller.setSoundSettings(it) },
                        previewSound = { cue ->
                            soundPlayer.play(cue, controller.state.soundSettings.volumePercent / 100f)
                        },
                        back = { controller.openToday() },
                    ),
                )
            } else {
                DayViewScreen(
                    state = state,
                    actions = DayViewScreenActions(
                        openSettings = { controller.openSettings() },
                        openMiniWindow = onOpenMiniWindow,
                        changeGoalTitle = { controller.setGoalTitle(it) },
                        changeGoalDeadline = { controller.setGoalDeadlineText(it) },
                        commitGoalDeadline = { controller.commitGoalDeadline() },
                        changeFocusIntention = { controller.setFocusIntention(it) },
                        changePomodoroDuration = { controller.changePomodoroDuration(it) },
                        startPomodoro = {
                            controller.startPomodoro()
                            controller.state.pomodoroEndMillis?.let {
                                onFocusAlarmChange(it, controller.state.focusIntention)
                            }
                        },
                        stopPomodoro = {
                            val intention = controller.state.focusIntention
                            controller.stopPomodoro()
                            onFocusAlarmChange(null, intention)
                        },
                        closePomodoro = { outcome ->
                            val intention = controller.state.focusIntention
                            controller.closePomodoro(outcome)
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
