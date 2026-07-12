package fr.dayview.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A "now" that always falls inside the default 08:00–18:00 day window,
 * whatever the machine timezone, so day-progress labels ("IL RESTE") are
 * deterministic. Only the local date is taken from the system clock; it does
 * not affect the time-of-day assertions.
 */
internal fun midWindowNow(): Instant {
    val tz = TimeZone.currentSystemDefault()
    val localNow = Clock.System.now().toLocalDateTime(tz)
    return LocalDateTime(
        year = localNow.year,
        month = localNow.month,
        day = localNow.day,
        hour = 13,
        minute = 0,
    ).toInstant(tz)
}

/** Builds a controller from a seeded snapshot + fixed clock — the production path. */
internal fun seededController(
    snapshot: DayPreferencesSnapshot,
    now: Instant = midWindowNow(),
): DayViewController = DayViewController(
    preferences = InMemoryDayPreferences(snapshot),
    scope = CoroutineScope(Dispatchers.Unconfined),
    initialSnapshot = snapshot,
    initialNow = now,
)

/** A full [SettingsScreenActions] of no-ops with the relevant callbacks overridable. */
internal fun noopSettingsActions(
    changeStartTime: (Int) -> Unit = {},
    changeEndTime: (Int) -> Unit = {},
    changeShowSeconds: (Boolean) -> Unit = {},
    changeSoundSettings: (SoundSettings) -> Unit = {},
    previewSound: (SoundCue) -> Unit = {},
    back: () -> Unit = {},
): SettingsScreenActions = SettingsScreenActions(
    changeStartTime = changeStartTime,
    changeEndTime = changeEndTime,
    changeShowSeconds = changeShowSeconds,
    changeMonochromeMenuBarIcon = null,
    changeLaunchAtLogin = null,
    changeSoundSettings = changeSoundSettings,
    previewSound = previewSound,
    back = back,
)

/** A full [DayViewScreenActions] of no-ops with the relevant callbacks overridable. */
internal fun noopDayViewActions(
    openSettings: () -> Unit = {},
    changeFocusIntention: (String) -> Unit = {},
    changePomodoroDuration: (Int) -> Unit = {},
    startPomodoro: () -> Unit = {},
    stopPomodoro: () -> Unit = {},
    closePomodoro: (FocusClosureOutcome) -> Unit = {},
): DayViewScreenActions = DayViewScreenActions(
    openSettings = openSettings,
    openMiniWindow = null,
    changeGoalTitle = {},
    changeGoalDeadline = {},
    commitGoalDeadline = {},
    changeGoalStart = {},
    commitGoalStart = {},
    changeFocusIntention = changeFocusIntention,
    changePomodoroDuration = changePomodoroDuration,
    startPomodoro = startPomodoro,
    stopPomodoro = stopPomodoro,
    closePomodoro = closePomodoro,
    addDetour = { _, _ -> },
    updateDetour = { _, _ -> },
    removeDetour = {},
    addDetourEpisode = {},
)

internal fun noReminders(): FocusReminderUiState = FocusReminderUiState(
    showDriftReminder = false,
    dismissDriftReminder = {},
    showResumeRitual = false,
    dismissResumeRitual = {},
)

/** Renders [DayViewScreen] forced into the wide layout so Goal/Focus panels render inline. */
@Composable
internal fun WideDayView(
    state: DayViewUiState,
    actions: DayViewScreenActions,
    reminders: FocusReminderUiState = noReminders(),
) {
    DayViewTheme {
        Box(Modifier.requiredSize(1100.dp, 900.dp)) {
            DayViewScreen(state = state, actions = actions, reminders = reminders)
        }
    }
}

/** Wires a [DayViewScreenActions] bundle straight to the controller (as App.kt does). */
internal fun controllerDayViewActions(controller: DayViewController): DayViewScreenActions = DayViewScreenActions(
    openSettings = { controller.openSettings() },
    openMiniWindow = null,
    changeGoalTitle = { controller.setGoalTitle(it) },
    changeGoalDeadline = { controller.setGoalDeadlineText(it) },
    commitGoalDeadline = { controller.commitGoalDeadline() },
    changeGoalStart = { controller.setGoalStartText(it) },
    commitGoalStart = { controller.commitGoalStart() },
    changeFocusIntention = { controller.setFocusIntention(it) },
    changePomodoroDuration = { controller.changePomodoroDuration(it) },
    startPomodoro = { controller.startPomodoro() },
    stopPomodoro = { controller.stopPomodoro() },
    closePomodoro = { controller.closePomodoro(it) },
    addDetour = { motif, durationMinutes -> controller.addDetour(motif, durationMinutes) },
    updateDetour = { index, episode -> controller.updateDetour(index, episode) },
    removeDetour = { controller.removeDetour(it) },
    addDetourEpisode = { controller.addDetourEpisode(it) },
)
