package fr.dayview.app

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private class RecordingHistoryFileSystem : HistoryFileSystem {
    val files = mutableMapOf<String, String>()

    override fun read(name: String): String? = files[name]

    override fun writeAtomic(
        name: String,
        text: String,
    ) {
        files[name] = text
    }

    override fun list(): List<String> = files.keys.toList()
}

class DayHistoryArchivingTest {
    @Test
    fun crossingMidnightArchivesTheDayThatEnded() = runTest {
        val fs = RecordingHistoryFileSystem()
        val start = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = start,
            history = FileDayHistoryStore(fs),
        )
        // Give the day something worth archiving, so the record cannot be mistaken for the
        // empty one an unconfigured day would produce.
        controller.setGoalTitle("Ship it")
        controller.addDetour("email", 15, "inbox")
        val endedDayKey = dayKeyOf(start)

        controller.tick(start + 1.days)
        runCurrent()

        val archived = fs.files[endedDayKey.toString()]
        assertNotNull(archived, "the day that ended should have been archived")
        val record = DayHistoryCodec.decode(archived)
        assertEquals(endedDayKey, record?.dayKey)
        assertEquals("Ship it", record?.goalTitle)
        assertEquals(1, record?.detours?.size, "the day's detours belong in its record")
    }

    @Test
    fun theDayInProgressIsNeverArchived() = runTest {
        val fs = RecordingHistoryFileSystem()
        val start = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = start,
            history = FileDayHistoryStore(fs),
        )
        // Give the day something worth archiving, so the same-day tick is genuinely
        // stopped by the day-key-equality guard rather than by the empty-state early
        // return in maybeArchivePreviousDay().
        controller.setGoalTitle("Ship it")
        controller.addDetour("email", 15, "inbox")

        controller.tick(start + 1.hours)
        runCurrent()

        assertEquals(emptyMap(), fs.files, "today is still in progress and must not be archived")
    }

    /**
     * The primary route for an app that gets quit at night: nothing runs `tick()` before the
     * next launch, so the previous day must be archived from `DayViewController`'s init path
     * (`maybeArchivePreviousDay`), not just from a live rollover tick. This is also the only
     * test that seeds presence/session intervals through `initialFocusPresenceIntervals` /
     * `initialFocusSessionIntervals` and checks they survive into the written record — the
     * entire reason the presence loader was changed to return stale-day intervals raw.
     */
    @Test
    fun coldLaunchArchivesYesterdayWithDetoursAndPresence() = runTest {
        val fs = RecordingHistoryFileSystem()
        val start = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        val today = start + 1.days
        val yesterdayKey = dayKeyOf(start)
        // Comfortably inside yesterday's [00:00, 23:59] window (startMinutes/endMinutes below),
        // so toHistoryRecord's window filter keeps them.
        val presenceInterval = FocusPresenceInterval(start + 1.hours, start + 2.hours)
        val sessionInterval = FocusPresenceInterval(start + 3.hours, start + 4.hours)

        DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(
                startMinutes = 0,
                endMinutes = 1439,
                detoursDayKey = yesterdayKey,
                detours = listOf(DetourEpisode(start, start + 15.minutes, "email", "inbox")),
            ),
            initialNow = today,
            history = FileDayHistoryStore(fs),
            initialFocusPresenceIntervals = listOf(presenceInterval),
            initialFocusSessionIntervals = listOf(sessionInterval),
        )
        runCurrent()

        val archived = fs.files[yesterdayKey.toString()]
        assertNotNull(archived, "a cold launch on a new day should archive yesterday from init")
        val record = DayHistoryCodec.decode(archived)
        assertEquals(yesterdayKey, record?.dayKey)
        assertEquals(1, record?.detours?.size, "yesterday's detours belong in its record")
        assertEquals(
            listOf(presenceInterval),
            record?.focusPresenceIntervals,
            "presence intervals seeded at cold launch must survive into the archived record",
        )
        assertEquals(
            listOf(sessionInterval),
            record?.focusSessionIntervals,
            "session intervals seeded at cold launch must survive into the archived record",
        )
    }
}
