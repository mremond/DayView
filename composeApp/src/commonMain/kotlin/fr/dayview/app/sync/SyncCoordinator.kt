package fr.dayview.app.sync

import fr.dayview.app.DayPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SyncStatus { Idle, Syncing, Ok, KeyError, Failed, NotConfigured }

/**
 * Drives the sync loop: loads the encryption key and endpoint config, runs [SyncEngine]
 * against the current preferences snapshot, and applies the result. Concurrent triggers
 * are coalesced — a call arriving while a sync is already running just requests one more
 * pass after the in-flight run finishes, rather than queuing up a call per trigger.
 */
class SyncCoordinator(
    private val deviceId: String,
    private val keyStore: SecureKeyStore,
    private val statePersistence: SyncStatePersistence,
    private val preferences: DayPreferences,
    private val transportFactory: (SyncConfig) -> SyncTransport,
    private val codecFactory: (RawSyncKey) -> PayloadCodec,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {
    private val _status = MutableStateFlow(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val mutex = Mutex()
    private var rerunRequested = false

    suspend fun syncNow() {
        if (mutex.isLocked) {
            rerunRequested = true
            return
        }
        mutex.withLock {
            do {
                rerunRequested = false
                runOnce()
            } while (rerunRequested)
        }
    }

    private suspend fun runOnce() {
        val key = keyStore.loadKey()
        val config = keyStore.loadConfig()
        if (key == null || config == null) {
            _status.value = SyncStatus.NotConfigured
            return
        }
        _status.value = SyncStatus.Syncing
        val engine = SyncEngine(transportFactory(config), codecFactory(key), deviceId)
        val local = preferences.snapshots.first()
        val state = statePersistence.load()
        _status.value =
            when (val result = engine.sync(local, state, now())) {
                is SyncResult.Applied -> {
                    preferences.persist(result.snapshot)
                    statePersistence.save(result.state)
                    SyncStatus.Ok
                }
                SyncResult.UpToDate -> SyncStatus.Ok
                SyncResult.KeyError -> SyncStatus.KeyError
                is SyncResult.Failed -> SyncStatus.Failed
            }
    }
}
