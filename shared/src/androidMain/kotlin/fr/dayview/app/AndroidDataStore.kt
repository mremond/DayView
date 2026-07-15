package fr.dayview.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File

fun androidDayPreferences(context: Context): DayPreferencesStore {
    val appContext = context.applicationContext
    val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        migrations = listOf(SharedPreferencesMigration(appContext, "dayview_preferences")),
    ) {
        File(appContext.filesDir, "datastore/dayview.preferences_pb")
    }
    return DayPreferencesStore(dataStore)
}
