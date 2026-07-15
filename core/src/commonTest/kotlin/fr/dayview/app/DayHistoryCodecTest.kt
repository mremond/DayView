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
            BusyInterval(
                Instant.fromEpochMilliseconds(9_000L),
                Instant.fromEpochMilliseconds(9_500L),
                listOf("", "Retro|1:1|pipe"),
                "cal-b",
            ),
        ),
        calendarNames = mapOf("cal-a" to "Work = life"),
        netTimeSettings = NetTimeSettings(enabled = true, includedCalendarIds = setOf("cal-a", "cal-b")),
        focusPresenceIntervals = listOf(FocusPresenceInterval(Instant.fromEpochMilliseconds(3_000L), Instant.fromEpochMilliseconds(4_000L))),
        focusSessionIntervals = emptyList(),
        focusSessionRecords = emptyList(),
        detours = listOf(DetourEpisode(Instant.fromEpochMilliseconds(5_000L), Instant.fromEpochMilliseconds(6_000L), "slack", "reading threads")),
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
    fun roundTripPreservesFocusSessionIntervals() {
        val record = sample().copy(
            focusSessionIntervals = listOf(
                FocusPresenceInterval(
                    Instant.fromEpochMilliseconds(1_000_000L),
                    Instant.fromEpochMilliseconds(2_000_000L),
                ),
            ),
        )
        val decoded = DayHistoryCodec.decode(DayHistoryCodec.encode(record))
        assertEquals(record.focusSessionIntervals, decoded?.focusSessionIntervals)
    }

    @Test
    fun decodesLegacyRecordWithoutSessionLineAsEmpty() {
        val record = sample()
        val legacy = DayHistoryCodec.encode(record)
            .lines()
            .filterNot { it.startsWith("session=") }
            .joinToString("\n")
        val decoded = DayHistoryCodec.decode(legacy)
        assertEquals(emptyList(), decoded?.focusSessionIntervals)
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

    private fun busyInterval(titles: List<String>) = BusyInterval(
        Instant.fromEpochMilliseconds(1_000L),
        Instant.fromEpochMilliseconds(2_000L),
        titles,
        "cal-a",
    )

    @Test
    fun emptyTitleListRoundTrips() {
        val interval = busyInterval(emptyList())
        val decoded = decodeBusyIntervals(encodeBusyIntervals(listOf(interval)))
        assertEquals(listOf(interval), decoded)
    }

    @Test
    fun singleEmptyStringTitleRoundTripsDistinctlyFromEmptyList() {
        val interval = busyInterval(listOf(""))
        val decoded = decodeBusyIntervals(encodeBusyIntervals(listOf(interval)))
        assertEquals(listOf(interval), decoded)
        assertEquals(listOf(""), decoded.single().titles)
    }

    @Test
    fun titlesWithDelimitersAndEmptyEntriesRoundTrip() {
        val interval = busyInterval(listOf("", "B"))
        val decoded = decodeBusyIntervals(encodeBusyIntervals(listOf(interval)))
        assertEquals(listOf(interval), decoded)

        val delimiterHeavy = busyInterval(listOf("Standup, daily", "1:1|pipe"))
        val decodedDelimiterHeavy = decodeBusyIntervals(encodeBusyIntervals(listOf(delimiterHeavy)))
        assertEquals(listOf(delimiterHeavy), decodedDelimiterHeavy)
    }
}
