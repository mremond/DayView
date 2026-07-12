package fr.dayview.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

@Composable
fun DayViewApp(
    preferences: DayPreferences = DefaultDayPreferences,
    monochromeMenuBarIcon: Boolean? = null,
    onMonochromeMenuBarIconChange: ((Boolean) -> Unit)? = null,
    launchAtLogin: Boolean? = null,
    onLaunchAtLoginChange: ((Boolean) -> Unit)? = null,
    onOpenMiniWindow: (() -> Unit)? = null,
    onFocusAlarmChange: (end: Instant?, intention: String) -> Unit = { _, _ -> },
    onRequestCalendarPermission: (() -> Unit)? = null,
    showFocusDriftReminder: Boolean = false,
    onDismissFocusDriftReminder: () -> Unit = {},
    showFocusResumeRitual: Boolean = false,
    onDismissFocusResumeRitual: () -> Unit = {},
    scheduleSoundAlerts: Boolean = true,
    runningApps: () -> List<AppRef> = { emptyList() },
    focusPresenceIntervals: List<FocusPresenceInterval> = emptyList(),
) {
    val initialThemeSnapshot = remember(preferences) { runBlocking { preferences.snapshots.first() } }
    val themeSnapshot by preferences.snapshots.collectAsState(initial = initialThemeSnapshot)
    DayViewTheme(themeMode = themeSnapshot.themeMode) { colors ->
        val baseDensity = LocalDensity.current
        // Override only fontScale (not density): every .sp grows, every .dp layout
        // measurement stays put. On Android baseDensity.fontScale already reflects the
        // OS font setting, so the in-app slider stacks on top of it; on desktop it is the
        // sole text-scale control.
        val scaledDensity = Density(baseDensity.density, baseDensity.fontScale * themeSnapshot.fontScale)
        CompositionLocalProvider(LocalDensity provides scaledDensity) {
            Surface(modifier = Modifier.fillMaxSize(), color = colors.ink) {
                val scope = rememberCoroutineScope()
                val initialSnapshot = remember(preferences) { runBlocking { preferences.snapshots.first() } }
                val controller = remember(preferences) { DayViewController(preferences, scope, initialSnapshot) }
                val state = controller.state
                // Recompute when the user opens Settings to edit the on-goal apps, rather
                // than freezing the check at launch (when no target app may be running yet).
                var hasRunningApps by remember { mutableStateOf(false) }
                LaunchedEffect(state.destination) {
                    if (state.destination == DayViewDestination.SETTINGS) {
                        hasRunningApps = runningApps().isNotEmpty()
                    }
                }
                val soundPlayer = remember { createSoundCuePlayer() }
                val soundScheduler = remember { SoundAlertScheduler() }
                val calendarSource = remember { createCalendarSource() }
                val calendarScope = rememberCoroutineScope()
                var calendarPermissionProbe by remember { mutableIntStateOf(0) }

                LaunchedEffect(preferences) {
                    preferences.snapshots.collect { controller.onPreferencesChanged(it) }
                }

                LaunchedEffect(focusPresenceIntervals) {
                    controller.setFocusPresenceIntervals(focusPresenceIntervals)
                }

                val netMinute = state.now.toEpochMilliseconds() / 60_000L
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
                                val (start, end) = dayWindow(state.now, state.startMinutes, state.endMinutes)
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
                    if (state.settingsCategory != null) {
                        controller.closeSettingsCategory()
                    } else {
                        controller.openToday()
                    }
                }
                DisposableEffect(soundPlayer) {
                    onDispose { soundPlayer.close() }
                }
                LaunchedEffect(state.showSeconds, state.pomodoroEnd) {
                    while (true) {
                        val now = Clock.System.now()
                        controller.tick(now)
                        val current = controller.state
                        val refreshDelay = if (current.showSeconds || current.pomodoroEnd != null) {
                            1_000L
                        } else {
                            60_000L - now.toEpochMilliseconds() % 60_000L
                        }
                        delay(refreshDelay)
                    }
                }
                // The ticker above relies on a coroutine delay that does not advance
                // during device deep sleep, so the shown time can lag after the screen
                // wakes. Re-read the clock on every resume to correct it immediately.
                // Platform-specific: Android observes the lifecycle; desktop is a no-op
                // (observing the desktop lifecycle's eventFlow stalls the frame clock).
                RefreshClockOnResumeEffect(now = { Clock.System.now() }, tick = controller::tick)
                LaunchedEffect(
                    state.now,
                    state.startMinutes,
                    state.endMinutes,
                    state.soundSettings,
                    scheduleSoundAlerts,
                    state.focusIsActive,
                ) {
                    if (scheduleSoundAlerts) {
                        val cue = soundScheduler.observe(
                            now = state.now,
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
                            changeThemeMode = { controller.setThemeMode(it) },
                            changeFontScale = { controller.setFontScale(it) },
                            openCategory = { controller.openSettingsCategory(it) },
                            closeCategory = { controller.closeSettingsCategory() },
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
                                controller.state.pomodoroEnd?.let {
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
                            addDetour = { motif, durationMinutes -> controller.addDetour(motif, durationMinutes) },
                            updateDetour = { index, episode -> controller.updateDetour(index, episode) },
                            removeDetour = { controller.removeDetour(it) },
                            addDetourEpisode = { controller.addDetourEpisode(it) },
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
}
