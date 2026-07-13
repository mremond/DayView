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

    fun loadDeviceId(): String?

    fun storeDeviceId(id: String)

    fun clear()
}

/**
 * Returns the stored device id, or generates one via [generate], persists it, and
 * returns it. The generator is injected so this stays testable; the real caller
 * passes a random-UUID/hex generator. [SyncCoordinator] uses the result only as an
 * LWW tie-breaker in merges.
 */
fun SecureKeyStore.deviceIdOrCreate(generate: () -> String): String = loadDeviceId() ?: generate().also { storeDeviceId(it) }

class InMemorySecureKeyStore : SecureKeyStore {
    private var key: RawSyncKey? = null
    private var config: SyncConfig? = null
    private var deviceId: String? = null

    override fun loadKey() = key

    override fun storeKey(key: RawSyncKey) {
        this.key = key
    }

    override fun loadConfig() = config

    override fun storeConfig(config: SyncConfig) {
        this.config = config
    }

    override fun loadDeviceId() = deviceId

    override fun storeDeviceId(id: String) {
        deviceId = id
    }

    override fun clear() {
        key = null
        config = null
        deviceId = null
    }
}
