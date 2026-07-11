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
            goalDeadlineMillis = 123_456_789L,
            pomodoroMinutes = 45,
            pomodoroEndMillis = null,
            focusIntention = "Focus",
        )
        store.persist(snapshot)
        assertEquals(snapshot, store.snapshots.first())
    }
}
