package fr.dayview.app.sync

import fr.dayview.app.DayHistoryStore
import fr.dayview.app.DayPreferences
import fr.dayview.app.FocusContributionStore
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
    private val focusContributionStore: FocusContributionStore? = null,
) {
    private val _status = MutableStateFlow(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val mutex = Mutex()

    suspend fun syncNow() {
        mutex.withLock { runOnce() }
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
        val focusSync = focusContributionStore?.let {
            FocusContributionSync(it, transport, HistoryBlobCodec(key), HistoryKey(key), deviceId)
        }
        val engine = SyncEngine(transport, codecFactory(key), deviceId, historySync = historySync, focusSync = focusSync)
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
