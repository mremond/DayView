package fr.dayview.app

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class DayPreferencesStoreTest {
    private fun newStore(fs: FakeFileSystem): DayPreferencesStore {
        // OkioStorage tracks "active" canonical paths in a process-wide registry to guard
        // against two DataStores sharing one file, regardless of which FakeFileSystem backs
        // them. Each call needs its own path so independent tests don't collide on it.
        val uniquePath = "/prefs-${Random.nextLong()}.preferences_pb".toPath()
        val storage = OkioStorage(
            fileSystem = fs,
            serializer = PreferencesSerializer,
            producePath = { uniquePath },
        )
        val dataStore = PreferenceDataStoreFactory.create(storage = storage)
        return DayPreferencesStore(dataStore)
    }

    @Test
    fun missingValuesFallBackToDefaults() = runTest {
        val store = newStore(FakeFileSystem())
        assertEquals(DayPreferencesSnapshot(), store.snapshots.first())
    }

    @Test
    fun persistThenReadRoundTrips() = runTest {
        val store = newStore(FakeFileSystem())
        val snapshot = DayPreferencesSnapshot(
            startMinutes = 9 * 60,
            endMinutes = 17 * 60,
            showSeconds = false,
            soundSettings = SoundSettings(enabled = true, volumePercent = 55),
            goalTitle = "Ship it",
            goalDeadline = Instant.fromEpochMilliseconds(123_456_789L),
            pomodoroMinutes = 45,
            pomodoroEnd = null,
            focusIntention = "Focus",
        )
        store.persist(snapshot)
        assertEquals(snapshot, store.snapshots.first())
    }

    @Test
    fun instantFieldsRoundTripNullAndValueThroughTheSentinel() = runTest {
        // Mirror of persistThenReadRoundTrips: a null instant must store as the -1L
        // sentinel and read back null, while a present instant round-trips unchanged.
        val store = newStore(FakeFileSystem())
        val snapshot = DayPreferencesSnapshot(
            goalDeadline = null,
            goalStart = Instant.fromEpochMilliseconds(1_700_000_000_000L),
            pomodoroEnd = Instant.fromEpochMilliseconds(1_700_000_001_000L),
        )
        store.persist(snapshot)

        val read = store.snapshots.first()
        assertEquals(null, read.goalDeadline)
        assertEquals(snapshot.goalStart, read.goalStart)
        assertEquals(snapshot.pomodoroEnd, read.pomodoroEnd)
    }

    @Test
    fun onGoalAppsRoundTrip() = runTest {
        val store = newStore(FakeFileSystem())
        val snapshot = DayPreferencesSnapshot(
            onGoalApps = setOf(
                AppRef("com.processone.draftline", "Draftline"),
                AppRef("com.apple.dt.Xcode", "Xcode"),
            ),
        )
        store.persist(snapshot)
        assertEquals(snapshot.onGoalApps, store.snapshots.first().onGoalApps)
    }

    @Test
    fun detoursRoundTrip() = runTest {
        val store = newStore(FakeFileSystem())
        val snapshot = DayPreferencesSnapshot(
            detoursDayKey = 20_646L,
            detours = listOf(
                DetourEpisode(
                    Instant.fromEpochMilliseconds(1_000L),
                    Instant.fromEpochMilliseconds(2_000L),
                    "Appel urgent",
                ),
            ),
            recentDetourCategories = listOf("Appel urgent", "Slack"),
        )
        store.persist(snapshot)
        assertEquals(snapshot, store.snapshots.first())
    }

    @Test
    fun calendarBusyLayerRoundTrips() = runTest {
        val store = newStore(FakeFileSystem())
        val snapshot = DayPreferencesSnapshot(
            busyDayKey = 20_646L,
            busyIntervals = listOf(
                BusyInterval(
                    Instant.fromEpochMilliseconds(1_000L),
                    Instant.fromEpochMilliseconds(2_000L),
                    listOf("Standup, daily", "1:1|pipe"),
                    "cal-a",
                ),
            ),
            availableCalendars = listOf(
                CalendarInfo("cal-a", "Work = life"),
                CalendarInfo("cal-b", "Perso"),
            ),
        )
        store.persist(snapshot)
        val read = store.snapshots.first()
        assertEquals(snapshot.busyDayKey, read.busyDayKey)
        assertEquals(snapshot.busyIntervals, read.busyIntervals)
        assertEquals(snapshot.availableCalendars, read.availableCalendars)
    }

    @Test
    fun absentCalendarBusyLayerRestoresEmpty() = runTest {
        val store = newStore(FakeFileSystem())
        val read = store.snapshots.first()
        assertEquals(-1L, read.busyDayKey)
        assertEquals(emptyList(), read.busyIntervals)
        assertEquals(emptyList(), read.availableCalendars)
    }

    @Test
    fun themeModeRoundTrips() = runTest {
        val store = newStore(FakeFileSystem())
        val snapshot = DayPreferencesSnapshot(themeMode = ThemeMode.DARK)
        store.persist(snapshot)
        assertEquals(ThemeMode.DARK, store.snapshots.first().themeMode)
    }

    @Test
    fun missingThemeModeFallsBackToSystem() = runTest {
        val store = newStore(FakeFileSystem())
        assertEquals(ThemeMode.SYSTEM, store.snapshots.first().themeMode)
    }

    @Test
    fun persistsAndRestoresCleanSessionLedger() = runTest {
        val store = newStore(FakeFileSystem())
        val ledger = CleanSessionLedger(dayKey = 42L, cleanToday = 3, streakDays = 5, streakLastDayKey = 41L)
        store.persist(DayPreferencesSnapshot(cleanSessions = ledger))
        assertEquals(ledger, store.snapshots.first().cleanSessions)
    }

    @Test
    fun absentLedgerKeysRestoreEmptyLedger() = runTest {
        val store = newStore(FakeFileSystem())
        assertEquals(CleanSessionLedger(), store.snapshots.first().cleanSessions)
    }

    @Test
    fun fontScaleRoundTripsAndDefaultsToOne() = runTest {
        val store = newStore(FakeFileSystem())
        // Absent value falls back to the 1.0 default.
        assertEquals(1.0f, store.snapshots.first().fontScale)

        store.persist(DayPreferencesSnapshot(fontScale = 1.25f))
        assertEquals(1.25f, store.snapshots.first().fontScale)
    }

    @Test
    fun fontScaleIsCoercedIntoRangeOnRead() = runTest {
        val store = newStore(FakeFileSystem())
        store.persist(DayPreferencesSnapshot(fontScale = 9.0f))
        assertEquals(1.5f, store.snapshots.first().fontScale)
    }

    @Test
    fun plannedObligationsRoundTrip() = runTest {
        val store = newStore(FakeFileSystem())
        val snapshot = DayPreferencesSnapshot(
            plannedObligationsDayKey = 20_646L,
            plannedObligations = listOf("Appel client", "Facture"),
        )
        store.persist(snapshot)
        val read = store.snapshots.first()
        assertEquals(20_646L, read.plannedObligationsDayKey)
        assertEquals(listOf("Appel client", "Facture"), read.plannedObligations)
    }

    @Test
    fun persistsAndReloadsCompletedObligations() = runTest {
        val store = newStore(FakeFileSystem())
        store.persist(
            DayPreferencesSnapshot(
                plannedObligationsDayKey = 19000,
                plannedObligations = listOf("b"),
                plannedObligationsCompleted = listOf("a"),
            ),
        )
        val loaded = store.snapshots.first()
        assertEquals(listOf("a"), loaded.plannedObligationsCompleted)
    }

    @Test
    fun focusSessionIntervalsRoundTripThroughTheStore() = runTest {
        val store = newStore(FakeFileSystem())
        val intervals = listOf(
            FocusPresenceInterval(Instant.fromEpochMilliseconds(1_000), Instant.fromEpochMilliseconds(2_000)),
            FocusPresenceInterval(Instant.fromEpochMilliseconds(3_000), Instant.fromEpochMilliseconds(4_000)),
        )
        store.persist(DayPreferencesSnapshot(focusSessionDayKey = 20260, focusSessionIntervals = intervals))
        val read = store.snapshots.first()
        assertEquals(20260L, read.focusSessionDayKey)
        assertEquals(intervals, read.focusSessionIntervals)
    }

    @Test
    fun persistsAndReloadsFocusSessionRecords() = runTest {
        val store = newStore(FakeFileSystem())
        val records = listOf(
            FocusSessionRecord(
                Instant.fromEpochMilliseconds(1_000),
                Instant.fromEpochMilliseconds(2_000),
                "ship it",
                FocusClosureOutcome.COMPLETED,
            ),
        )
        store.persist(DayPreferencesSnapshot(focusSessionRecords = records, focusSessionRecordsDayKey = 42L))
        val reloaded = store.snapshots.first()
        assertEquals(records, reloaded.focusSessionRecords)
        assertEquals(42L, reloaded.focusSessionRecordsDayKey)
    }

    @Test
    fun sessionMinutesAndBreakStartRoundTrip() = runTest {
        val store = newStore(FakeFileSystem())
        val snapshot = DayPreferencesSnapshot(
            pomodoroSessionMinutes = 5,
            breakStart = Instant.fromEpochMilliseconds(123_456_000L),
        )
        store.persist(snapshot)
        val restored = store.snapshots.first()
        assertEquals(5, restored.pomodoroSessionMinutes)
        assertEquals(Instant.fromEpochMilliseconds(123_456_000L), restored.breakStart)
    }

    @Test
    fun absentSessionKeysReadBackAsNull() = runTest {
        val store = newStore(FakeFileSystem())
        val restored = store.snapshots.first()
        assertNull(restored.pomodoroSessionMinutes)
        assertNull(restored.breakStart)
    }
}
