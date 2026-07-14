package fr.dayview.app

import androidx.compose.foundation.isSystemInDarkTheme
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
import dev.whyoleg.cryptography.random.CryptographyRandom
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.dayview_tray
import fr.dayview.app.generated.resources.dayview_tray_monochrome
import fr.dayview.app.generated.resources.desktop_break
import fr.dayview.app.generated.resources.desktop_day_over
import fr.dayview.app.generated.resources.desktop_focus
import fr.dayview.app.generated.resources.desktop_goal_deadline_reached
import fr.dayview.app.generated.resources.desktop_goal_hours
import fr.dayview.app.generated.resources.desktop_goal_title_hours
import fr.dayview.app.generated.resources.desktop_hide_dayview
import fr.dayview.app.generated.resources.desktop_menubar_break
import fr.dayview.app.generated.resources.desktop_nudge_title
import fr.dayview.app.generated.resources.desktop_open_dayview
import fr.dayview.app.generated.resources.desktop_open_full_window
import fr.dayview.app.generated.resources.desktop_quit_dayview
import fr.dayview.app.generated.resources.desktop_show_mini_window
import fr.dayview.app.generated.resources.desktop_today_remaining
import fr.dayview.app.generated.resources.focus_single_thing
import fr.dayview.app.sync.Aes256GcmCodec
import fr.dayview.app.sync.FileSyncStatePersistence
import fr.dayview.app.sync.HttpSyncTransport
import fr.dayview.app.sync.SyncCoordinator
import fr.dayview.app.sync.createSyncHttpClient
import fr.dayview.app.sync.desktopSecureKeyStore
import fr.dayview.app.sync.deviceIdOrCreate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import java.io.File
import kotlin.math.ceil
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.Instant

fun main() {
    // The macOS application menu (next to the Apple logo) shows the JVM main-class name
    // ("MainKt") when DayView runs unbundled via Gradle. Set the AWT application name
    // before the toolkit initialises so the menu reads "DayView" instead.
    System.setProperty("apple.awt.application.name", "DayView")
    // Let the JVM honor an explicit light/dark window appearance we set below,
    // instead of always following the OS. "system" is the neutral starting point.
    System.setProperty("apple.awt.application.appearance", "system")
    runApplication()
}

@OptIn(ExperimentalStdlibApi::class)
private fun runApplication() = application {
    val preferences = remember { desktopDayPreferences() }
    val history = remember { createDayHistoryStore() }
    val scope = rememberCoroutineScope()
    val syncKeyStore = remember { desktopSecureKeyStore() }
    val syncCoordinator = remember {
        val stateFile = File(System.getProperty("user.home"), ".dayview/sync-state.json")
        val statePersistence = FileSyncStatePersistence(
            read = { stateFile.takeIf { it.exists() }?.readText() },
            write = {
                stateFile.parentFile?.mkdirs()
                stateFile.writeText(it)
            },
        )
        val deviceId = syncKeyStore.deviceIdOrCreate { CryptographyRandom.nextBytes(16).toHexString() }
        SyncCoordinator(
            deviceId = deviceId,
            keyStore = syncKeyStore,
            statePersistence = statePersistence,
            preferences = preferences,
            transportFactory = { HttpSyncTransport(createSyncHttpClient(), it.baseUrl, it.userId, it.token) },
            codecFactory = { Aes256GcmCodec(it) },
            scope = CoroutineScope(Dispatchers.Default),
            now = { Clock.System.now().toEpochMilliseconds() },
            historyStore = history,
        )
    }
    val loginLauncher = remember { MacLoginLauncher() }
    val focusStatusItem = remember { MacFocusStatusItem() }
    val dockBadge = remember { MacDockBadge() }
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
    val initialFocusSession = remember { runBlocking { preferences.loadFocusSession() } }
    val sessionAccumulator = remember {
        PresenceAccumulator(
            presentStates = setOf(OnGoalState.ON_GOAL, OnGoalState.NEUTRAL),
            bridge = 120.seconds,
            minInterval = 60.seconds,
            interruptionGap = 15.seconds,
        ).also {
            val (day, intervals) = initialFocusSession
            if (day >= 0) it.restore(intervals, day)
        }
    }
    var focusSessionIntervals by remember { mutableStateOf(initialFocusSession.second) }
    var lastSessionSave by remember { mutableStateOf(Instant.DISTANT_PAST) }
    var wasFocusActive by remember { mutableStateOf(false) }
    val cleanlinessTracker = remember { SessionCleanlinessTracker() }
    var sessionOffGoal by remember { mutableStateOf(Duration.ZERO) }

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
                nudgeNotifier.notify(
                    title = getString(Res.string.desktop_nudge_title),
                    body = FocusNudgeCopy.body(
                        currentPreferences.focusIntention,
                        getString(Res.string.focus_single_thing),
                    ),
                )
            } else if (!focusIsActive) {
                focusDriftReminderId = null
                focusResumeRitualId = null
            }

            val dayKey = currentNow
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .date.toEpochDays()
            val classification = classifyFrontmost(frontmostBundleId, onGoalBundleIds)
            sessionOffGoal = cleanlinessTracker.observe(currentNow, currentPomodoroEnd, classification)
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
            val updatedSession = when {
                focusIsActive -> sessionAccumulator.observe(currentNow, classification, dayKey)
                wasFocusActive -> sessionAccumulator.endSession()
                else -> focusSessionIntervals
            }
            if (updatedSession != focusSessionIntervals) {
                val sessionStructuralChange = updatedSession.size != focusSessionIntervals.size
                focusSessionIntervals = updatedSession
                if (sessionStructuralChange || currentNow - lastSessionSave >= 30.seconds) {
                    preferences.saveFocusSession(dayKey, updatedSession)
                    lastSessionSave = currentNow
                }
            }
            wasFocusActive = focusIsActive
            delay(1_000)
        }
    }

    // Keep the native window chrome (title bar, traffic lights) matching the
    // in-app theme even when it disagrees with the OS appearance.
    val appearanceIsDark = preferenceSnapshot.themeMode.resolveIsDark(isSystemInDarkTheme())
    LaunchedEffect(appearanceIsDark) {
        System.setProperty(
            "apple.awt.application.appearance",
            if (appearanceIsDark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua",
        )
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
        stringResource(Res.string.desktop_day_over)
    } else {
        stringResource(
            Res.string.desktop_today_remaining,
            dayProgress.remainingHours.toString(),
            dayProgress.remainingMinutes.toString().padStart(2, '0'),
        )
    }
    val goalStatus = goalDeadline?.let { deadline ->
        val working = calculateGoalWorkingTime(now, deadline, startMinutes, endMinutes)
        val hours = ceil(working.toDouble(DurationUnit.HOURS)).toLong()
        when {
            deadline <= now -> stringResource(Res.string.desktop_goal_deadline_reached)
            goalTitle.isBlank() -> stringResource(Res.string.desktop_goal_hours, hours.toString())
            else -> stringResource(Res.string.desktop_goal_title_hours, goalTitle.take(24), hours.toString())
        }
    }
    val pomodoro = calculatePomodoroProgress(now, pomodoroMinutes, pomodoroEnd)
    val focusStatus = when (pomodoro.status) {
        PomodoroStatus.ACTIVE -> listOfNotNull(
            stringResource(Res.string.desktop_focus),
            focusIntention.take(24).takeIf(String::isNotBlank),
            formatPomodoroClock(pomodoro),
        ).joinToString(" · ")
        PomodoroStatus.BREAK -> stringResource(Res.string.desktop_break, formatBreakClock(pomodoro))
        PomodoroStatus.IDLE -> null
    }

    LaunchedEffect(pomodoro.status, pomodoro.remaining.inWholeSeconds) {
        focusStatusItem.update(
            when (pomodoro.status) {
                PomodoroStatus.ACTIVE -> formatPomodoroCompactMinutes(pomodoro)
                PomodoroStatus.BREAK ->
                    getString(Res.string.desktop_menubar_break, pomodoro.breakElapsed.inWholeMinutes.toString())
                PomodoroStatus.IDLE -> null
            },
        )
    }
    LaunchedEffect(focusDriftReminderId) {
        dockBadge.update(focusDriftReminderId != null)
    }
    DisposableEffect(Unit) {
        onDispose {
            focusStatusItem.close()
            dockBadge.close()
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
        Item(
            if (isMiniWindowVisible) {
                stringResource(Res.string.desktop_open_full_window)
            } else {
                stringResource(Res.string.desktop_show_mini_window)
            },
        ) {
            isMiniWindowVisible = !isMiniWindowVisible
            isWindowVisible = !isMiniWindowVisible
        }
        Item(
            if (isWindowVisible) {
                stringResource(Res.string.desktop_hide_dayview)
            } else {
                stringResource(Res.string.desktop_open_dayview)
            },
        ) {
            if (isWindowVisible) {
                isWindowVisible = false
            } else {
                isMiniWindowVisible = false
                isWindowVisible = true
            }
        }
        Item(stringResource(Res.string.desktop_quit_dayview), onClick = ::exitApplication)
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
                history = history,
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
                focusSessionIntervals = focusSessionIntervals,
                sessionOffGoal = sessionOffGoal,
                secureKeyStore = syncKeyStore,
                syncCoordinator = syncCoordinator,
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
            window.minimumSize = java.awt.Dimension(200, 300)
            DayViewMiniApp(
                progress = dayProgress,
                showSeconds = showSeconds,
                now = now,
                goalTitle = goalTitle,
                goalDeadline = goalDeadline,
                pomodoro = pomodoro,
                focusIntention = focusIntention,
                fontScale = preferenceSnapshot.fontScale,
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
                onCloseFocus = { outcome ->
                    scope.launch {
                        val s = preferences.snapshots.first()
                        preferences.persist(
                            closeFocusSnapshot(s.copy(pomodoroMinutes = pomodoroMinutes), now, sessionOffGoal, outcome),
                        )
                    }
                },
                onOpenMainWindow = {
                    isMiniWindowVisible = false
                    isWindowVisible = true
                },
            )
        }
    }
}
