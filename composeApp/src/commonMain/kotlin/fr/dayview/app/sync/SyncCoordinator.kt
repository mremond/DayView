package fr.dayview.app.sync

import fr.dayview.app.DayHistoryStore
import fr.dayview.app.DayPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SyncStatus { Idle, Syncing, Ok, KeyError, Failed, NotConfigured }

enum class SyncSetupResult { Success, NotConfigured, AuthenticationFailed, KeyMismatch, ConnectionFailed }

/**
 * Drives the sync loop: loads the encryption key and endpoint config, runs [SyncEngine]
 * against the current preferences snapshot, and applies the result. Concurrent triggers
 * are serialized through [mutex] rather than coalesced with a flag: a trigger arriving
 * while a sync is in flight simply waits for the mutex and then runs with a fresh
 * snapshot, so a local change made mid-sync is never lost. Any resulting redundant run
 * is cheap because [SyncEngine] short-circuits to [SyncResult.UpToDate] once the remote
 * already holds the merged document, and triggers are naturally rate-limited (debounced
 * preference writes plus app-resume), so this doesn't create unbounded pile-up.
 */
class SyncCoordinator(
    private val deviceId: String,
    private val keyStore: SecureKeyStore,
    private val statePersistence: SyncStatePersistence,
    private val preferences: DayPreferences,
    private val transportFactory: (SyncConfig) -> SyncTransport,
    private val codecFactory: (RawSyncKey) -> PayloadCodec,
    // Reserved for wiring app-level sync triggers (debounced writes, resume) in a later task.
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val historyStore: DayHistoryStore? = null,
) {
    private val _status = MutableStateFlow(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val mutex = Mutex()

    suspend fun syncNow() {
        mutex.withLock { runOnce() }
    }

    /**
     * Verifies authentication and the E2EE key without writing, then performs the
     * first real sync. Success therefore means the device is connected, can read
     * existing remote state, and has completed a synchronization cycle.
     */
    suspend fun verifyAndSync(): SyncSetupResult {
        val key = keyStore.loadKey() ?: return SyncSetupResult.NotConfigured
        val config = keyStore.loadConfig() ?: return SyncSetupResult.NotConfigured
        _status.value = SyncStatus.Syncing
        return try {
            val remote = transportFactory(config).pull()
            if (remote != null) codecFactory(key).decrypt(remote.payload)
            syncNow()
            when (_status.value) {
                SyncStatus.Ok -> SyncSetupResult.Success
                SyncStatus.KeyError -> SyncSetupResult.KeyMismatch
                SyncStatus.NotConfigured -> SyncSetupResult.NotConfigured
                else -> SyncSetupResult.ConnectionFailed
            }
        } catch (_: SyncAuthenticationException) {
            _status.value = SyncStatus.Failed
            SyncSetupResult.AuthenticationFailed
        } catch (_: SyncKeyMismatchException) {
            _status.value = SyncStatus.KeyError
            SyncSetupResult.KeyMismatch
        } catch (_: Throwable) {
            _status.value = SyncStatus.Failed
            SyncSetupResult.ConnectionFailed
        }
    }

    /** Resets persisted sync state (base revision/document) so the next sync starts fresh. */
    suspend fun reset() {
        mutex.withLock { statePersistence.clear() }
    }

    private suspend fun runOnce() {
        val key = keyStore.loadKey()
        val config = keyStore.loadConfig()
        if (key == null || config == null) {
            _status.value = SyncStatus.NotConfigured
            return
        }
        _status.value = SyncStatus.Syncing
        val transport = transportFactory(config)
        val historySync = historyStore?.let {
            HistorySync(it, transport, HistoryBlobCodec(key), HistoryKey(key))
        }
        val engine = SyncEngine(transport, codecFactory(key), deviceId, historySync = historySync)
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
