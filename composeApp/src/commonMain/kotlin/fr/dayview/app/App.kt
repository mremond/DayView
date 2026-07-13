package fr.dayview.app

import androidx.compose.foundation.layout.BoxWithConstraints
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
import fr.dayview.app.sync.RawSyncKey
import fr.dayview.app.sync.RecoveryPhrase
import fr.dayview.app.sync.SecureKeyStore
import fr.dayview.app.sync.SyncCoordinator
import fr.dayview.app.sync.SyncOnResumeEffect
import fr.dayview.app.sync.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(FlowPreview::class)
@Composable
internal fun DayViewApp(
    preferences: DayPreferences = DefaultDayPreferences,
    history: DayHistoryStore = InMemoryDayHistoryStore(),
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
    sessionOffGoal: Duration = Duration.ZERO,
    secureKeyStore: SecureKeyStore? = null,
    syncCoordinator: SyncCoordinator? = null,
) {
    val initialThemeSnapshot = remember(preferences) { runBlocking { preferences.snapshots.first() } }
    val themeSnapshot by preferences.snapshots.collectAsState(initial = initialThemeSnapshot)
    DayViewTheme(themeMode = themeSnapshot.themeMode, uses24Hour = rememberUses24HourClock()) { colors ->
        val baseDensity = LocalDensity.current
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Automatic whole-UI zoom derived from the available space, so a physically
            // large tablet (e.g. a Supernote e-ink) viewed at reading distance shows the
            // ring and layout comfortably large instead of at phone size. Scaling `density`
            // grows the dp-based UI (ring, spacing) as well as text; the font slider then
            // stacks on top via `fontScale`. Desktop opts out.
            // Measure the scale against a keyboard-independent screen dimension: with
            // `adjustResize`, the live constraints shrink when the IME opens, which would
            // otherwise re-scale the UI and dismiss the focused field's keyboard (seen on
            // large Supernote canvases).
            val scaleMinDp = stableScaleMinDimensionDp(minOf(maxWidth, maxHeight).value)
            val autoScale = autoDisplayScale(scaleMinDp, platformAutoScaleEnabled())
            val scaledDensity = Density(
                baseDensity.density * autoScale,
                baseDensity.fontScale * themeSnapshot.fontScale,
            )
            CompositionLocalProvider(
                LocalDensity provides scaledDensity,
                LocalPreferenceFontScale provides themeSnapshot.fontScale,
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = colors.ink) {
                    val scope = rememberCoroutineScope()
                    val initialSnapshot = remember(preferences) { runBlocking { preferences.snapshots.first() } }
                    // Buffered so a burst of local edits collapses into a single pending signal;
                    // the debounced collector below turns it into a single sync trigger.
                    val localWriteSignal = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
                    val controller = remember(preferences) {
                        DayViewController(
                            preferences,
                            scope,
                            initialSnapshot,
                            history = history,
                            initialFocusPresenceIntervals = focusPresenceIntervals,
                            onLocalWrite = { localWriteSignal.tryEmit(Unit) },
                        )
                    }
                    val state by controller.stateFlow.collectAsState()
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
                    var calendarChangeProbe by remember { mutableIntStateOf(0) }

                    // Synchronous key/config store: not a Flow, so the UI state is a
                    // Compose snapshot state re-derived only by the callbacks below.
                    var syncConfig by remember { mutableStateOf(secureKeyStore?.loadConfig()) }
                    var syncHasKey by remember { mutableStateOf(secureKeyStore?.loadKey() != null) }
                    val fallbackSyncStatus = remember { MutableStateFlow(SyncStatus.Idle) }
                    val syncStatus by (syncCoordinator?.status ?: fallbackSyncStatus).collectAsState()

                    // Sync I/O (network + keystore/state-file reads) must never run on the
                    // Compose UI dispatcher; every syncNow() call — automatic or manual —
                    // is routed through this helper. No-op when sync isn't configured.
                    fun launchSync() {
                        scope.launch(Dispatchers.IO) { syncCoordinator?.syncNow() }
                    }
                    if (syncCoordinator != null) {
                        // Startup: run once when the coordinator becomes available.
                        LaunchedEffect(syncCoordinator) { launchSync() }
                        // Foreground/resume (Android) or periodic (desktop, see the actual).
                        SyncOnResumeEffect { launchSync() }
                        // Debounced local-write trigger. `onLocalWrite` above only fires from
                        // DayViewController's local-write path (persistState), never from a
                        // sync-applied change (that goes through preferences.persist directly),
                        // so this cannot create a sync/apply echo loop.
                        LaunchedEffect(syncCoordinator) {
                            localWriteSignal.debounce(3.seconds).collect { launchSync() }
                        }
                    }

                    LaunchedEffect(preferences) {
                        preferences.snapshots.collect { controller.onPreferencesChanged(it) }
                    }

                    LaunchedEffect(focusPresenceIntervals) {
                        controller.setFocusPresenceIntervals(focusPresenceIntervals)
                    }

                    LaunchedEffect(sessionOffGoal) {
                        controller.setSessionOffGoal(sessionOffGoal)
                    }

                    val netMinute = state.now.toEpochMilliseconds() / 60_000L
                    LaunchedEffect(
                        netMinute,
                        state.netTimeSettings,
                        state.startMinutes,
                        state.endMinutes,
                        calendarPermissionProbe,
                        calendarChangeProbe,
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

                    // Watch the calendar provider so external edits (a new event, a moved
                    // meeting) refresh the busy layer promptly while the app is foregrounded,
                    // instead of waiting for the next minute tick or a resume. No-op on
                    // platforms whose source cannot push changes (desktop, Noop).
                    DisposableEffect(calendarSource, state.netTimeSettings.enabled) {
                        val handle = if (state.netTimeSettings.enabled) {
                            runCatching { calendarSource.observeChanges { calendarChangeProbe++ } }.getOrNull()
                        } else {
                            null
                        }
                        onDispose { handle?.let { runCatching { it.close() } } }
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
                    PlatformBackHandler(
                        enabled = state.destination == DayViewDestination.SETTINGS ||
                            state.destination == DayViewDestination.HISTORY,
                    ) {
                        when (state.destination) {
                            DayViewDestination.SETTINGS -> {
                                if (state.settingsCategory != null) {
                                    controller.closeSettingsCategory()
                                } else {
                                    controller.openToday()
                                }
                            }
                            DayViewDestination.HISTORY -> controller.closeHistory()
                            DayViewDestination.TODAY -> {}
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

                    when (state.destination) {
                        DayViewDestination.SETTINGS -> SettingsScreen(
                            state = state,
                            platformState = SettingsPlatformUiState(
                                monochromeMenuBarIcon = monochromeMenuBarIcon,
                                launchAtLogin = launchAtLogin,
                                netTimeSupported = calendarSource.isSupported(),
                                onGoalSupported = hasRunningApps,
                                runningApps = runningApps,
                                syncConfig = syncConfig,
                                syncStatus = syncStatus,
                                syncHasKey = syncHasKey,
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
                                changeSyncConfig = { cfg ->
                                    // UI state updates synchronously on the main thread; the
                                    // keystore write (disk I/O) is pushed off it.
                                    val previous = syncConfig
                                    syncConfig = cfg
                                    scope.launch(Dispatchers.IO) { secureKeyStore?.storeConfig(cfg) }
                                    // A different server/user must not reuse the old baseDocument
                                    // as a merge base for the new endpoint.
                                    if (previous != null &&
                                        (previous.userId != cfg.userId || previous.baseUrl != cfg.baseUrl)
                                    ) {
                                        scope.launch(Dispatchers.IO) { syncCoordinator?.reset() }
                                    }
                                },
                                generateSyncKey = {
                                    // Key generation is cheap and stays synchronous so the
                                    // recovery phrase can be returned immediately; only the
                                    // keystore persistence goes to IO.
                                    val key = RawSyncKey.generate()
                                    syncHasKey = true
                                    scope.launch(Dispatchers.IO) { secureKeyStore?.storeKey(key) }
                                    RecoveryPhrase.encode(key).joinToString(" ")
                                },
                                pasteSyncKey = { phrase ->
                                    val key = RecoveryPhrase.decodePhrase(phrase)
                                    if (key != null) {
                                        syncHasKey = true
                                        scope.launch(Dispatchers.IO) { secureKeyStore?.storeKey(key) }
                                        true
                                    } else {
                                        false
                                    }
                                },
                                syncNow = { launchSync() },
                                clearSyncKey = {
                                    syncConfig = null
                                    syncHasKey = false
                                    scope.launch(Dispatchers.IO) {
                                        secureKeyStore?.clear()
                                        syncCoordinator?.reset()
                                    }
                                },
                                back = { controller.openToday() },
                            ),
                        )
                        DayViewDestination.HISTORY -> {
                            val selected = state.selectedHistoryDay
                            val record = state.historyWeek.firstOrNull { it.dayKey == selected }?.record
                            if (selected != null && record != null) {
                                HistoryDayScreen(record = record, onBack = { controller.closeHistory() })
                            } else {
                                HistoryWeekScreen(
                                    days = state.historyWeek,
                                    onSelectDay = { controller.openHistoryDay(it) },
                                    onBack = { controller.closeHistory() },
                                )
                            }
                        }
                        DayViewDestination.TODAY -> DayViewScreen(
                            state = state,
                            actions = DayViewScreenActions(
                                openSettings = { controller.openSettings() },
                                onOpenHistory = { controller.openHistory() },
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
                                addDetour = { category, durationMinutes, description -> controller.addDetour(category, durationMinutes, description) },
                                updateDetour = { index, episode -> controller.updateDetour(index, episode) },
                                removeDetour = { controller.removeDetour(it) },
                                addDetourEpisode = { controller.addDetourEpisode(it) },
                                forgetDetourCategory = { controller.forgetRecentDetourCategory(it) },
                                addPlannedObligation = { controller.addPlannedObligation(it) },
                                removePlannedObligation = { controller.removePlannedObligation(it) },
                                completePlannedObligation = controller::completePlannedObligation,
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
}
