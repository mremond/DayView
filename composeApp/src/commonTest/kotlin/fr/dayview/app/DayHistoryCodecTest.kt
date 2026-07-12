package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class DayHistoryCodecTest {
    private fun sample() = DayHistoryRecord(
        dayKey = 20_000L,
        startMinutes = 8 * 60,
        endMinutes = 18 * 60,
        focusIntention = "Line one\nwith a newline, and = signs",
        busyIntervals = listOf(
            BusyInterval(
                Instant.fromEpochMilliseconds(1_000L),
                Instant.fromEpochMilliseconds(2_000L),
                listOf("Standup, daily", "1:1 with Alex"),
                "cal-a",
            ),
        ),
        calendarNames = mapOf("cal-a" to "Work = life"),
        netTimeSettings = NetTimeSettings(enabled = true, includedCalendarIds = setOf("cal-a", "cal-b")),
        focusPresenceIntervals = listOf(FocusPresenceInterval(Instant.fromEpochMilliseconds(3_000L), Instant.fromEpochMilliseconds(4_000L))),
        detours = listOf(DetourEpisode(Instant.fromEpochMilliseconds(5_000L), Instant.fromEpochMilliseconds(6_000L), "slack")),
        cleanSessions = CleanSessionLedger(dayKey = 20_000L, cleanToday = 3, streakDays = 5, streakLastDayKey = 20_000L),
        pomodoroMinutes = 25,
        pomodoroEnd = Instant.fromEpochMilliseconds(7_000L),
        goalTitle = "Deliver",
        goalDeadline = Instant.fromEpochMilliseconds(8_000L),
        goalStart = Instant.fromEpochMilliseconds(500L),
    )

    @Test
    fun encodeThenDecodeIsLossless() {
        val record = sample()
        assertEquals(record, DayHistoryCodec.decode(DayHistoryCodec.encode(record)))
    }

    @Test
    fun encodedTextStartsWithVersionHeader() {
        assertEquals("dayhistory v1", DayHistoryCodec.encode(sample()).lineSequence().first())
    }

    @Test
    fun unknownVersionDecodesToNull() {
        assertNull(DayHistoryCodec.decode("dayhistory v999\ndayKey=1"))
    }

    @Test
    fun garbageDecodesToNull() {
        assertNull(DayHistoryCodec.decode("not a record at all"))
        assertNull(DayHistoryCodec.decode(""))
    }

    @Test
    fun missingRequiredKeyDecodesToNull() {
        // Header present but no dayKey line.
        assertNull(DayHistoryCodec.decode("dayhistory v1\nstart=480"))
    }
}
