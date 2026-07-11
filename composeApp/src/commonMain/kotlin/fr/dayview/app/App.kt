package fr.dayview.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    onRequestCalendarPermission: (() -> Unit)? = null,
    showFocusDriftReminder: Boolean = false,
    onDismissFocusDriftReminder: () -> Unit = {},
    showFocusResumeRitual: Boolean = false,
    onDismissFocusResumeRitual: () -> Unit = {},
    scheduleSoundAlerts: Boolean = true,
    runningApps: () -> List<AppRef> = { emptyList() },
    focusPresenceIntervals: List<FocusPresenceInterval> = emptyList(),
) {
    DayViewTheme { colors ->
        Surface(modifier = Modifier.fillMaxSize(), color = colors.ink) {
            val scope = rememberCoroutineScope()
            val controller = remember(preferences) { DayViewController(preferences, scope) }
            val state = controller.state
            val hasRunningApps = remember { runningApps().isNotEmpty() }
            val soundPlayer = remember { createSoundCuePlayer() }
            val soundScheduler = remember { SoundAlertScheduler() }
            val calendarSource = remember { createCalendarSource() }
            val calendarScope = rememberCoroutineScope()
            var calendarPermissionProbe by remember { mutableIntStateOf(0) }

            DisposableEffect(controller, preferences) {
                val stopObserving = preferences.observe { controller.onPreferencesChanged(it) }
                onDispose(stopObserving)
            }

            LaunchedEffect(focusPresenceIntervals) {
                controller.setFocusPresenceIntervals(focusPresenceIntervals)
            }

            val netMinute = state.nowMillis / 60_000L
            LaunchedEffect(
                netMinute,
                state.netTimeSettings,
                state.startMinutes,
                state.endMinutes,
                calendarPermissionProbe,
            ) {
                val (permission, busy, calendars) = withContext(Dispatchers.Default) {
                    if (!state.netTimeSettings.enabled) {
                        Triple(false, emptyList<BusyInterval>(), emptyList<CalendarInfo>())
                    } else {
                        val granted = runCatching { calendarSource.hasPermission() }.getOrDefault(false)
                        if (granted) {
                            val (start, end) = dayWindowMillis(state.nowMillis, state.startMinutes, state.endMinutes)
                            val intervals = runCatching {
                                calendarSource.busyIntervals(start, end, state.netTimeSettings.includedCalendarIds)
                            }.getOrDefault(emptyList())
                            val available = runCatching { calendarSource.availableCalendars() }
                                .getOrDefault(emptyList())
                            Triple(true, intervals, available)
                        } else {
                            Triple(false, emptyList<BusyInterval>(), emptyList<CalendarInfo>())
                        }
                    }
                }
                controller.updateNetTimeData(permission, busy, calendars)
            }

            val onRequestCalendarAccess: () -> Unit = {
                val platformHook = onRequestCalendarPermission
                if (platformHook != null) {
                    platformHook()
                    calendarPermissionProbe++
                } else {
                    calendarScope.launch {
                        withContext(Dispatchers.Default) { runCatching { calendarSource.requestPermission() } }
                        calendarPermissionProbe++
                    }
                }
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
                        netTimeSupported = calendarSource.isSupported(),
                        onGoalSupported = hasRunningApps,
                        runningApps = runningApps,
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
                        changeNetTimeSettings = {
                            controller.setNetTimeSettings(it)
                            calendarPermissionProbe++
                        },
                        requestCalendarPermission = onRequestCalendarAccess,
                        changeOnGoalApps = { controller.setOnGoalApps(it) },
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
                        changeGoalStart = { controller.setGoalStartText(it) },
                        commitGoalStart = { controller.commitGoalStart() },
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
