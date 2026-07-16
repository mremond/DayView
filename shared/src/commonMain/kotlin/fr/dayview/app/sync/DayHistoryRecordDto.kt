package fr.dayview.app.sync

import kotlinx.serialization.Serializable

@Serializable
data class BusyIntervalDto(
    val start: Long,
    val end: Long,
    val titles: List<String> = emptyList(),
    val calendarId: String = "",
)

@Serializable data class PresenceDto(val start: Long, val end: Long)

@Serializable
data class DetourEpisodeDto(val start: Long, val end: Long, val category: String, val description: String = "")

@Serializable
data class FocusSessionRecordDto(val start: Long, val end: Long, val intention: String, val outcome: String)

@Serializable
data class DayHistoryRecordDto(
    val schemaVersion: Int,
    val dayKey: Long,
    val startMinutes: Int,
    val endMinutes: Int,
    val focusIntention: String,
    val busyIntervals: List<BusyIntervalDto>,
    val calendarNames: Map<String, String>,
    val netEnabled: Boolean,
    val netCalendars: List<String>,
    val focusPresence: List<PresenceDto>,
    val focusSession: List<PresenceDto> = emptyList(),
    val focusSessionRecords: List<FocusSessionRecordDto> = emptyList(),
    val detours: List<DetourEpisodeDto>,
    val cleanSessions: CleanDto,
    val pomodoroMinutes: Int,
    val pomodoroEnd: Long,
    val goalTitle: String,
    val goalDeadline: Long,
    val goalStart: Long,
)
