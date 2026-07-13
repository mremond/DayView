package fr.dayview.app

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.emptyPreferences
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSHomeDirectory

/**
 * File-backed [DayPreferences] for the native macOS app, stored under
 * ~/Library/Application Support/DayView. Reuses the shared [DayPreferencesStore] encoding.
 */
fun macosDayPreferences(): DayPreferences {
    val dir = "${NSHomeDirectory()}/Library/Application Support/DayView".toPath()
    FileSystem.SYSTEM.createDirectories(dir)
    val path = dir / "dayview.preferences_pb"
    val storage = OkioStorage(
        fileSystem = FileSystem.SYSTEM,
        serializer = PreferencesSerializer,
        producePath = { path },
    )
    return DayPreferencesStore(
        PreferenceDataStoreFactory.create(
            storage = storage,
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        ),
    )
}
