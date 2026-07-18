package fr.dayview.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlin.time.Clock

// The presence pair reuses the Compose/JVM app's key names: DayPreferencesStore has no such
// keys, so there is no collision. The session list deliberately does NOT reuse the JVM's
// "focus_session": that is already the shared store's own key, written from
// focusSessionDayKey on every persist — and that field stays -1 natively
// (derivesEngagedFromSessions is false), so sharing it would clobber the day key and make
// the intervals load as stale.
private val presenceDayKey = longPreferencesKey("focus_presence_day")
private val presenceKey = stringPreferencesKey("focus_presence")
private val sessionKey = stringPreferencesKey("mac_focus_session")

/**
 * [PresencePersistence] over the macOS DataStore, using the shared interval codecs. Both
 * lists are written in one atomic edit under a single day key, so they cannot disagree
 * about which day they belong to.
 */
class MacosPresencePersistence(
    private val dataStore: DataStore<Preferences>,
) : PresencePersistence {
    override suspend fun load(): StoredPresence {
        val prefs = dataStore.data.first()
        val day = prefs[presenceDayKey] ?: -1L
        // Staleness applied at read time: yesterday's arcs can never resurrect.
        if (day != dayKeyOf(Clock.System.now())) return StoredPresence()
        return StoredPresence(
            dayKey = day,
            presence = decodeFocusPresence(prefs[presenceKey].orEmpty()),
            session = decodeFocusPresence(prefs[sessionKey].orEmpty()),
        )
    }

    override suspend fun save(
        dayKey: Long,
        presence: List<FocusPresenceInterval>,
        session: List<FocusPresenceInterval>,
    ) {
        dataStore.edit {
            it[presenceDayKey] = dayKey
            it[presenceKey] = encodeFocusPresence(presence)
            it[sessionKey] = encodeFocusPresence(session)
        }
    }
}
