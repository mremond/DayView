package fr.dayview.app.sync

import fr.dayview.app.BusyInterval
import fr.dayview.app.CleanSessionLedger
import fr.dayview.app.DayHistoryRecord
import fr.dayview.app.DetourEpisode
import fr.dayview.app.FocusPresenceInterval
import fr.dayview.app.NetTimeSettings
import kotlin.time.Instant

object HistoryRecordMapper {
    private const val NO_INSTANT = -1L

    private fun Instant?.toMillis(): Long = this?.toEpochMilliseconds() ?: NO_INSTANT
    private fun Long.toInstantOrNull(): Instant? = if (this == NO_INSTANT) null else Instant.fromEpochMilliseconds(this)

    private fun toDto(r: DayHistoryRecord) = DayHistoryRecordDto(
        schemaVersion = HISTORY_SCHEMA_VERSION,
        dayKey = r.dayKey,
        startMinutes = r.startMinutes,
        endMinutes = r.endMinutes,
        focusIntention = r.focusIntention,
        busyIntervals = r.busyIntervals.map {
            BusyIntervalDto(it.start.toEpochMilliseconds(), it.end.toEpochMilliseconds(), it.titles, it.calendarId)
        },
        calendarNames = r.calendarNames,
        netEnabled = r.netTimeSettings.enabled,
        netCalendars = r.netTimeSettings.includedCalendarIds.toList(),
        focusPresence = r.focusPresenceIntervals.map { PresenceDto(it.start.toEpochMilliseconds(), it.end.toEpochMilliseconds()) },
        focusSession = r.focusSessionIntervals.map { PresenceDto(it.start.toEpochMilliseconds(), it.end.toEpochMilliseconds()) },
        detours = r.detours.map { DetourEpisodeDto(it.start.toEpochMilliseconds(), it.end.toEpochMilliseconds(), it.category, it.description) },
        cleanSessions = CleanDto(r.cleanSessions.dayKey, r.cleanSessions.cleanToday, r.cleanSessions.streakDays, r.cleanSessions.streakLastDayKey),
        pomodoroMinutes = r.pomodoroMinutes,
        pomodoroEnd = r.pomodoroEnd.toMillis(),
        goalTitle = r.goalTitle,
        goalDeadline = r.goalDeadline.toMillis(),
        goalStart = r.goalStart.toMillis(),
    )

    private fun toRecord(d: DayHistoryRecordDto) = DayHistoryRecord(
        dayKey = d.dayKey,
        startMinutes = d.startMinutes,
        endMinutes = d.endMinutes,
        focusIntention = d.focusIntention,
        busyIntervals = d.busyIntervals.map {
            BusyInterval(Instant.fromEpochMilliseconds(it.start), Instant.fromEpochMilliseconds(it.end), it.titles, it.calendarId)
        },
        calendarNames = d.calendarNames,
        netTimeSettings = NetTimeSettings(enabled = d.netEnabled, includedCalendarIds = d.netCalendars.toSet()),
        focusPresenceIntervals = d.focusPresence.map { FocusPresenceInterval(Instant.fromEpochMilliseconds(it.start), Instant.fromEpochMilliseconds(it.end)) },
        focusSessionIntervals = d.focusSession.map { FocusPresenceInterval(Instant.fromEpochMilliseconds(it.start), Instant.fromEpochMilliseconds(it.end)) },
        // Session records are not yet part of the sync DTO/schema (Task 4/5 wired only the
        // local history codec); a synced record therefore reads back with no session records.
        focusSessionRecords = emptyList(),
        detours = d.detours.map { DetourEpisode(Instant.fromEpochMilliseconds(it.start), Instant.fromEpochMilliseconds(it.end), it.category, it.description) },
        cleanSessions = CleanSessionLedger(d.cleanSessions.dayKey, d.cleanSessions.cleanToday, d.cleanSessions.streakDays, d.cleanSessions.streakLastDayKey),
        pomodoroMinutes = d.pomodoroMinutes,
        pomodoroEnd = d.pomodoroEnd.toInstantOrNull(),
        goalTitle = d.goalTitle,
        goalDeadline = d.goalDeadline.toInstantOrNull(),
        goalStart = d.goalStart.toInstantOrNull(),
    )

    fun serialize(record: DayHistoryRecord): String = SyncJson.encodeToString(toDto(record))

    fun deserialize(json: String): DayHistoryRecord? = try {
        toRecord(SyncJson.decodeFromString<DayHistoryRecordDto>(json))
    } catch (e: Exception) {
        null
    }
}
