package fr.dayview.app.sync

import fr.dayview.app.CleanSessionLedger
import fr.dayview.app.DayPreferencesSnapshot
import fr.dayview.app.DetourEpisode
import fr.dayview.app.SoundSettings
import fr.dayview.app.ThemeMode
import kotlin.time.Instant

private const val NO_INSTANT = -1L

fun detourKey(e: DetourDto): String = "${e.start}|${e.end}|${e.motif}"

private fun Instant?.toMillisOrAbsent(): Long = this?.toEpochMilliseconds() ?: NO_INSTANT
private fun Long.toInstantOrNull(): Instant? = if (this == NO_INSTANT) null else Instant.fromEpochMilliseconds(this)

/** Keep [current]'s stamp if the value is unchanged from [base]; otherwise stamp with [now]. */
private fun <T> restamp(current: T, base: Versioned<T>?, now: Long, by: String): Versioned<T> = if (base != null && base.value == current) base else Versioned(current, Stamp(now, by))

fun buildDocument(
    snapshot: DayPreferencesSnapshot,
    base: SyncDocument?,
    deviceId: String,
    now: Long,
): SyncDocument {
    val fresh = Stamp(now, deviceId)
    return SyncDocument(
        schemaVersion = SYNC_SCHEMA_VERSION,
        dayWindow = restamp(DayWindow(snapshot.startMinutes, snapshot.endMinutes), base?.dayWindow, now, deviceId),
        showSeconds = restamp(snapshot.showSeconds, base?.showSeconds, now, deviceId),
        sound = restamp(snapshot.soundSettings.toDto(), base?.sound, now, deviceId),
        goal = restamp(
            GoalDto(snapshot.goalTitle, snapshot.goalDeadline.toMillisOrAbsent(), snapshot.goalStart.toMillisOrAbsent()),
            base?.goal,
            now,
            deviceId,
        ),
        pomodoro = restamp(PomodoroDto(snapshot.pomodoroMinutes, snapshot.pomodoroEnd.toMillisOrAbsent()), base?.pomodoro, now, deviceId),
        focusIntention = restamp(snapshot.focusIntention, base?.focusIntention, now, deviceId),
        themeMode = restamp(snapshot.themeMode.name, base?.themeMode, now, deviceId),
        netTimeEnabled = restamp(snapshot.netTimeSettings.enabled, base?.netTimeEnabled, now, deviceId),
        detours = buildDayScoped(
            dayKey = snapshot.detoursDayKey,
            values = snapshot.detours.map { DetourDto(it.start.toEpochMilliseconds(), it.end.toEpochMilliseconds(), it.motif) },
            keyOf = ::detourKey,
            base = base?.detours,
            fresh = fresh,
        ),
        plannedObligations = buildDayScoped(
            dayKey = snapshot.plannedObligationsDayKey,
            values = snapshot.plannedObligations,
            keyOf = { it },
            base = base?.plannedObligations,
            fresh = fresh,
        ),
        recentDetourMotifs = buildItems(snapshot.recentDetourMotifs, { it }, base?.recentDetourMotifs, fresh),
        cleanSessions = restamp(snapshot.cleanSessions.toDto(), base?.cleanSessions, now, deviceId),
    )
}

private fun <T> buildDayScoped(
    dayKey: Long,
    values: List<T>,
    keyOf: (T) -> String,
    base: DayScoped<T>?,
    fresh: Stamp,
): DayScoped<T> {
    // Only diff against base when it describes the same day; a new day starts clean.
    val sameDayBase = base?.takeIf { it.dayKey == dayKey }
    return DayScoped(dayKey, buildItems(values, keyOf, sameDayBase?.items, fresh))
}

private fun <T> buildItems(
    values: List<T>,
    keyOf: (T) -> String,
    baseItems: List<SyncItem<T>>?,
    fresh: Stamp,
): List<SyncItem<T>> {
    val baseByKey = baseItems.orEmpty().associateBy { it.id }
    val present = values.map { v ->
        val key = keyOf(v)
        val prior = baseByKey[key]
        if (prior != null && !prior.deleted && prior.value == v) {
            prior
        } else {
            SyncItem(key, v, deleted = false, stamp = fresh)
        }
    }
    val presentKeys = present.map { it.id }.toSet()
    val tombstones = baseByKey.values
        .filter { it.id !in presentKeys }
        .map { if (it.deleted) it else it.copy(deleted = true, stamp = fresh) }
    return present + tombstones
}

fun applyDocument(document: SyncDocument, local: DayPreferencesSnapshot): DayPreferencesSnapshot = local.copy(
    startMinutes = document.dayWindow.value.start,
    endMinutes = document.dayWindow.value.end,
    showSeconds = document.showSeconds.value,
    soundSettings = document.sound.value.toSettings(),
    goalTitle = document.goal.value.title,
    goalDeadline = document.goal.value.deadline.toInstantOrNull(),
    goalStart = document.goal.value.start.toInstantOrNull(),
    pomodoroMinutes = document.pomodoro.value.minutes,
    pomodoroEnd = document.pomodoro.value.end.toInstantOrNull(),
    focusIntention = document.focusIntention.value,
    themeMode = ThemeMode.entries.firstOrNull { it.name == document.themeMode.value } ?: local.themeMode,
    // preserve device-local calendar ids; only the enabled toggle is synced
    netTimeSettings = local.netTimeSettings.copy(enabled = document.netTimeEnabled.value),
    detoursDayKey = document.detours.dayKey,
    detours = document.detours.items.filterNot { it.deleted }
        .map { DetourEpisode(Instant.fromEpochMilliseconds(it.value.start), Instant.fromEpochMilliseconds(it.value.end), it.value.motif) },
    plannedObligationsDayKey = document.plannedObligations.dayKey,
    plannedObligations = document.plannedObligations.items.filterNot { it.deleted }.map { it.value },
    recentDetourMotifs = document.recentDetourMotifs.filterNot { it.deleted }.map { it.value },
    cleanSessions = document.cleanSessions.value.toLedger(),
    // onGoalApps and fontScale are left untouched by copy() → preserved
)

private fun SoundSettings.toDto() = SoundDto(enabled, startCueEnabled, intervalCueEnabled, endCueEnabled, intervalMinutes, volumePercent)
private fun SoundDto.toSettings() = SoundSettings(enabled, startCue, intervalCue, endCue, intervalMinutes, volumePercent)
private fun CleanSessionLedger.toDto() = CleanDto(dayKey, cleanToday, streakDays, streakLastDayKey)
private fun CleanDto.toLedger() = CleanSessionLedger(dayKey, cleanToday, streakDays, streakLastDayKey)
