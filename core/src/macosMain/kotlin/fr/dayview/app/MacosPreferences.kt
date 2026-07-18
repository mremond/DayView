package fr.dayview.app

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.emptyPreferences
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSHomeDirectory

/** The two macOS preference surfaces, both backed by the same DataStore over one file. */
class MacosPreferences(
    val dayPreferences: DayPreferences,
    val presencePersistence: PresencePersistence,
)

/**
 * File-backed preferences for the native macOS app, stored under
 * ~/Library/Application Support/DayView. Reuses the shared [DayPreferencesStore] encoding.
 *
 * Creates exactly ONE DataStore for the file and hands it to both surfaces: DataStore
 * requires a single instance per file, and a second one over the same path risks
 * corrupting it.
 */
fun macosPreferences(): MacosPreferences {
    val dir = "${NSHomeDirectory()}/Library/Application Support/DayView".toPath()
    FileSystem.SYSTEM.createDirectories(dir)
    val path = dir / "dayview.preferences_pb"
    val storage = OkioStorage(
        fileSystem = FileSystem.SYSTEM,
        serializer = PreferencesSerializer,
        producePath = { path },
    )
    val dataStore = PreferenceDataStoreFactory.create(
        storage = storage,
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    )
    return MacosPreferences(
        dayPreferences = DayPreferencesStore(dataStore),
        presencePersistence = MacosPresencePersistence(dataStore),
    )
}
