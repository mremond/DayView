package fr.dayview.app

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.prefs.Preferences as LegacyPreferences

private const val KEY_MONOCHROME = "monochrome_menu_bar_icon"
private val monochromeKey = booleanPreferencesKey(KEY_MONOCHROME)

class DesktopPreferences(
    private val dataStore: DataStore<Preferences>,
) : DayPreferences {
    private val store = DayPreferencesStore(dataStore)
    override val snapshots: Flow<DayPreferencesSnapshot> get() = store.snapshots
    override suspend fun persist(snapshot: DayPreferencesSnapshot) = store.persist(snapshot)

    val monochromeMenuBarIcon: Flow<Boolean> = dataStore.data.map { it[monochromeKey] ?: false }
    suspend fun loadMonochromeMenuBarIcon(): Boolean = monochromeMenuBarIcon.first()
    suspend fun saveMonochromeMenuBarIcon(monochrome: Boolean) {
        dataStore.edit { it[monochromeKey] = monochrome }
    }
}

fun desktopDayPreferences(
    legacy: LegacyPreferences = LegacyPreferences.userNodeForPackage(DesktopPreferences::class.java),
    file: File = File(System.getProperty("user.home"), ".dayview/dayview.preferences_pb"),
): DesktopPreferences {
    file.parentFile?.mkdirs()
    val dataStore = PreferenceDataStoreFactory.create(
        migrations = listOf(JavaPrefsMigration(legacy)),
    ) { file }
    return DesktopPreferences(dataStore)
}

private class JavaPrefsMigration(
    private val legacy: LegacyPreferences,
) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean = currentData.asMap().isEmpty() && legacyKeys().isNotEmpty()

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        val keys = legacyKeys()
        for (key in keys) {
            when (key) {
                "start_minutes", "end_minutes", "sound_interval_minutes", "sound_volume",
                "pomodoro_minutes",
                -> prefs[intPreferencesKey(key)] = legacy.getInt(key, 0)
                "goal_deadline", "goal_start", "pomodoro_end",
                -> prefs[longPreferencesKey(key)] = legacy.getLong(key, -1L)
                "show_seconds", "sound_enabled", "sound_start", "sound_interval", "sound_end",
                "net_time_enabled", "monochrome_menu_bar_icon",
                -> prefs[booleanPreferencesKey(key)] = legacy.getBoolean(key, false)
                else ->
                    prefs[stringPreferencesKey(key)] = legacy.get(key, "")
            }
        }
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {
        // Leave the legacy node intact: a one-way delete is unnecessary and risky if
        // the first read fails midway. The DataStore is authoritative once migrated.
    }

    private fun legacyKeys(): List<String> = try {
        legacy.keys().toList()
    } catch (e: java.util.prefs.BackingStoreException) {
        emptyList()
    }
}
