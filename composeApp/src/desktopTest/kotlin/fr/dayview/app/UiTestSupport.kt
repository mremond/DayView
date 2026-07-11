package fr.dayview.app

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
