package fr.dayview.app.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val PREFS_FILE_NAME = "dayview_sync_secret"
private const val KEY_SYNC_KEY = "sync_key"
private const val KEY_SYNC_CONFIG = "sync_config"

/**
 * Android [SecureKeyStore] backed by [EncryptedSharedPreferences], whose values
 * are encrypted at rest using a key held in the Android Keystore.
 */
fun androidSecureKeyStore(context: Context): SecureKeyStore {
    val appContext = context.applicationContext
    val masterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    val prefs = EncryptedSharedPreferences.create(
        appContext,
        PREFS_FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    return AndroidSecureKeyStore(prefs)
}

@OptIn(ExperimentalEncodingApi::class)
private class AndroidSecureKeyStore(private val prefs: SharedPreferences) : SecureKeyStore {
    override fun loadKey(): RawSyncKey? = prefs.getString(KEY_SYNC_KEY, null)?.let { RawSyncKey(Base64.decode(it)) }

    override fun storeKey(key: RawSyncKey) {
        prefs.edit().putString(KEY_SYNC_KEY, Base64.encode(key.bytes)).apply()
    }

    override fun loadConfig(): SyncConfig? = prefs.getString(KEY_SYNC_CONFIG, null)?.let { SyncJson.decodeFromString(it) }

    override fun storeConfig(config: SyncConfig) {
        prefs.edit().putString(KEY_SYNC_CONFIG, SyncJson.encodeToString(config)).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_SYNC_KEY).remove(KEY_SYNC_CONFIG).apply()
    }
}
