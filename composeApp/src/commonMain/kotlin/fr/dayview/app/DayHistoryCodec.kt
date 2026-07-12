package fr.dayview.app

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Instant

/**
 * Versioned, line-based text codec for [DayHistoryRecord]: a `dayhistory v1` header line
 * followed by `key=value` lines. Every free-text or newline-bearing value is Base64-encoded
 * so it never collides with the `\n`/`=` structure. Decoding is defensive: a missing header,
 * unknown version, missing key, or unparseable number returns `null` rather than throwing,
 * which callers treat as an empty day.
 */
@OptIn(ExperimentalEncodingApi::class)
internal object DayHistoryCodec {
    const val VERSION_HEADER = "dayhistory v1"
    private const val NONE = -1L

    private fun enc(text: String): String = Base64.encode(text.encodeToByteArray())
    private fun dec(b64: String): String = Base64.decode(b64).decodeToString()

    private fun encodeCalendarNames(names: Map<String, String>): String = names.entries.joinToString("\n") { "${enc(it.key)},${enc(it.value)}" }

    private fun decodeCalendarNames(encoded: String): Map<String, String> = encoded.split("\n").mapNotNull { line ->
        if (line.isEmpty()) return@mapNotNull null
        val parts = line.split(",", limit = 2)
        if (parts.size != 2) null else dec(parts[0]) to dec(parts[1])
    }.toMap()

    fun encode(record: DayHistoryRecord): String = buildString {
        appendLine(VERSION_HEADER)
        appendLine("dayKey=${record.dayKey}")
        appendLine("start=${record.startMinutes}")
        appendLine("end=${record.endMinutes}")
        appendLine("intention=${enc(record.focusIntention)}")
        appendLine("netEnabled=${record.netTimeSettings.enabled}")
        appendLine("netCalendars=${enc(record.netTimeSettings.includedCalendarIds.joinToString("\n"))}")
        appendLine("busy=${enc(encodeBusyIntervals(record.busyIntervals))}")
        appendLine("calNames=${enc(encodeCalendarNames(record.calendarNames))}")
        appendLine("presence=${enc(encodeFocusPresence(record.focusPresenceIntervals))}")
        appendLine("detours=${enc(encodeDetours(record.detours))}")
        appendLine("cleanDay=${record.cleanSessions.dayKey}")
        appendLine("cleanToday=${record.cleanSessions.cleanToday}")
        appendLine("streakDays=${record.cleanSessions.streakDays}")
        appendLine("streakLastDay=${record.cleanSessions.streakLastDayKey}")
        appendLine("pomodoroMinutes=${record.pomodoroMinutes}")
        appendLine("pomodoroEnd=${record.pomodoroEnd?.toEpochMilliseconds() ?: NONE}")
        appendLine("goalTitle=${enc(record.goalTitle)}")
        appendLine("goalDeadline=${record.goalDeadline?.toEpochMilliseconds() ?: NONE}")
        appendLine("goalStart=${record.goalStart?.toEpochMilliseconds() ?: NONE}")
    }

    fun decode(text: String): DayHistoryRecord? {
        val lines = text.split("\n")
        if (lines.firstOrNull()?.trim() != VERSION_HEADER) return null
        val map = lines.drop(1).mapNotNull { line ->
            if (line.isEmpty()) return@mapNotNull null
            val idx = line.indexOf('=')
            if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
        }.toMap()

        return try {
            fun req(key: String): String = map[key] ?: error("missing $key")
            fun instantOrNull(key: String): Instant? = req(key).toLong().takeIf { it != NONE }?.let { Instant.fromEpochMilliseconds(it) }

            DayHistoryRecord(
                dayKey = req("dayKey").toLong(),
                startMinutes = req("start").toInt(),
                endMinutes = req("end").toInt(),
                focusIntention = dec(req("intention")),
                busyIntervals = decodeBusyIntervals(dec(req("busy"))),
                calendarNames = decodeCalendarNames(dec(req("calNames"))),
                netTimeSettings = NetTimeSettings(
                    enabled = req("netEnabled").toBoolean(),
                    includedCalendarIds = dec(req("netCalendars")).split("\n").filter { it.isNotEmpty() }.toSet(),
                ),
                focusPresenceIntervals = decodeFocusPresence(dec(req("presence"))),
                detours = decodeDetours(dec(req("detours"))),
                cleanSessions = CleanSessionLedger(
                    dayKey = req("cleanDay").toLong(),
                    cleanToday = req("cleanToday").toInt(),
                    streakDays = req("streakDays").toInt(),
                    streakLastDayKey = req("streakLastDay").toLong(),
                ),
                pomodoroMinutes = req("pomodoroMinutes").toInt(),
                pomodoroEnd = instantOrNull("pomodoroEnd"),
                goalTitle = dec(req("goalTitle")),
                goalDeadline = instantOrNull("goalDeadline"),
                goalStart = instantOrNull("goalStart"),
            )
        } catch (e: Exception) {
            null
        }
    }
}
