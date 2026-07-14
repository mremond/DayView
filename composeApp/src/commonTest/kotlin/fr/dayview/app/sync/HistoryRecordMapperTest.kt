package fr.dayview.app.sync

import fr.dayview.app.BusyInterval
import fr.dayview.app.CleanSessionLedger
import fr.dayview.app.DayHistoryRecord
import fr.dayview.app.DetourEpisode
import fr.dayview.app.FocusPresenceInterval
import fr.dayview.app.NetTimeSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class HistoryRecordMapperTest {
    private val record = DayHistoryRecord(
        dayKey = 20100,
        startMinutes = 480,
        endMinutes = 1320,
        focusIntention = "ship the sync",
        busyIntervals = listOf(
            BusyInterval(Instant.fromEpochMilliseconds(1000), Instant.fromEpochMilliseconds(2000), listOf("Standup", "Focus"), "cal-1"),
        ),
        calendarNames = mapOf("cal-1" to "Work"),
        netTimeSettings = NetTimeSettings(enabled = true, includedCalendarIds = setOf("cal-1", "cal-2")),
        focusPresenceIntervals = listOf(FocusPresenceInterval(Instant.fromEpochMilliseconds(1500), Instant.fromEpochMilliseconds(1800))),
        detours = listOf(DetourEpisode(Instant.fromEpochMilliseconds(3000), Instant.fromEpochMilliseconds(4000), "email", "inbox zero")),
        cleanSessions = CleanSessionLedger(dayKey = 20100, cleanToday = 2, streakDays = 5, streakLastDayKey = 20100),
        pomodoroMinutes = 25,
        pomodoroEnd = Instant.fromEpochMilliseconds(5000),
        goalTitle = "Launch",
        goalDeadline = Instant.fromEpochMilliseconds(6000),
        goalStart = null,
    )

    @Test
    fun roundTripsWithoutLoss() {
        val restored = HistoryRecordMapper.deserialize(HistoryRecordMapper.serialize(record))
        assertEquals(record, restored)
    }

    @Test
    fun deserializeReturnsNullOnGarbage() {
        assertNull(HistoryRecordMapper.deserialize("not json"))
    }
}
