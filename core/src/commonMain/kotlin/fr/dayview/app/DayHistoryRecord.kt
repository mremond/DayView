package fr.dayview.app

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlin.time.Instant

/**
 * A faithful, immutable snapshot of one local day's ring inputs. Holds exactly the
 * day-scoped fields of [DayViewUiState]; the ring is re-derived from these via the same
 * projection used live, so history renders identically without duplicated drawing logic.
 */
data class DayHistoryRecord(
    val dayKey: Long,
    val startMinutes: Int,
    val endMinutes: Int,
    val focusIntention: String,
    val busyIntervals: List<BusyInterval>,
    val calendarNames: Map<String, String>,
    val netTimeSettings: NetTimeSettings,
    val focusPresenceIntervals: List<FocusPresenceInterval>,
    val detours: List<DetourEpisode>,
    val cleanSessions: CleanSessionLedger,
    val pomodoroMinutes: Int,
    val pomodoroEnd: Instant?,
    val goalTitle: String,
    val goalDeadline: Instant?,
    val goalStart: Instant?,
)

/** The instant on [dayKey]'s calendar day at the given minute-of-day offset. */
private fun instantAtMinutes(
    dayKey: Long,
    minutes: Int,
    timeZone: TimeZone,
): Instant = LocalDate.fromEpochDays(dayKey.toInt())
    .atTime(LocalTime(minutes / 60, minutes % 60))
    .toInstant(timeZone)

/** The instant at the end of the record's day window, used as the frozen "now". */
private fun DayHistoryRecord.frozenNow(timeZone: TimeZone): Instant = instantAtMinutes(dayKey, endMinutes, timeZone)

/**
 * Rebuild a [DayViewUiState] pinned to the recorded day, with `now` at the day's end so
 * the ring reads as a completed day. `showSeconds = false` keeps the replay at minute
 * precision (deterministic). Only day-scoped fields are meaningful; everything else takes
 * its default.
 */
fun DayHistoryRecord.toFrozenUiState(
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): DayViewUiState = DayViewUiState(
    now = frozenNow(timeZone),
    startMinutes = startMinutes,
    endMinutes = endMinutes,
    showSeconds = false,
    soundSettings = SoundSettings(),
    goalTitle = goalTitle,
    goalDeadlineText = "",
    goalDeadline = goalDeadline,
    goalStartText = "",
    goalStart = goalStart,
    pomodoroMinutes = pomodoroMinutes,
    pomodoroEnd = pomodoroEnd,
    focusIntention = focusIntention,
    netTimeSettings = netTimeSettings,
    busyDayKey = dayKey,
    availableCalendars = calendarNames.map { CalendarInfo(it.key, it.value) },
    busyIntervals = busyIntervals,
    focusPresenceIntervals = focusPresenceIntervals,
    detoursDayKey = dayKey,
    detours = detours,
    cleanSessions = cleanSessions,
)

/**
 * Extract the day-scoped subset of [DayViewUiState] for [dayKey]. Focus-presence
 * intervals are clipped to the record's day window so a running presence log doesn't
 * bleed into an archived day.
 */
internal fun DayViewUiState.toHistoryRecord(
    dayKey: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): DayHistoryRecord {
    val windowStart = instantAtMinutes(dayKey, startMinutes, timeZone)
    val windowEnd = instantAtMinutes(dayKey, endMinutes, timeZone)
    return DayHistoryRecord(
        dayKey = dayKey,
        startMinutes = startMinutes,
        endMinutes = endMinutes,
        focusIntention = focusIntention,
        busyIntervals = busyIntervals,
        calendarNames = availableCalendars.associate { it.id to it.displayName },
        netTimeSettings = netTimeSettings,
        focusPresenceIntervals = focusPresenceIntervals.filter { it.end > windowStart && it.start < windowEnd },
        detours = detours,
        cleanSessions = cleanSessions,
        pomodoroMinutes = pomodoroMinutes,
        pomodoroEnd = pomodoroEnd,
        goalTitle = goalTitle,
        goalDeadline = goalDeadline,
        goalStart = goalStart,
    )
}
