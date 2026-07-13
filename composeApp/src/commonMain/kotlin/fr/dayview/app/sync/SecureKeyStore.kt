package fr.dayview.app.sync

/**
 * Persists the sync encryption key and endpoint configuration outside plain
 * application preferences. Platform implementations back this with a secure
 * store (Android Keystore-backed EncryptedSharedPreferences, a permission-locked
 * file on desktop); [InMemorySecureKeyStore] is used for tests and as a default
 * before a platform store is wired in.
 */
interface SecureKeyStore {
    fun loadKey(): RawSyncKey?

    fun storeKey(key: RawSyncKey)

    fun loadConfig(): SyncConfig?

    fun storeConfig(config: SyncConfig)

    fun clear()
}

class InMemorySecureKeyStore : SecureKeyStore {
    private var key: RawSyncKey? = null
    private var config: SyncConfig? = null

    override fun loadKey() = key

    override fun storeKey(key: RawSyncKey) {
        this.key = key
    }

    override fun loadConfig() = config

    override fun storeConfig(config: SyncConfig) {
        this.config = config
    }

    override fun clear() {
        key = null
        config = null
    }
}
