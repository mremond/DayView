package fr.dayview.app.sync

import fr.dayview.app.DayHistoryStore
import fr.dayview.app.DayPreferences
import fr.dayview.app.FocusContributionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds

enum class SyncStatus { Idle, Syncing, Ok, KeyError, Failed, NotConfigured, NeedsChoice }

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
 * After a transient failure ([SyncResult.Failed] not caused by authentication), the
 * coordinator retries on its own with exponential backoff, so sync recovers without
 * an external trigger once the network returns.
 */
class SyncCoordinator(
    private val deviceId: String,
    private val keyStore: SecureKeyStore,
    private val statePersistence: SyncStatePersistence,
    private val preferences: DayPreferences,
    private val transportFactory: (SyncConfig) -> SyncTransport,
    private val codecFactory: (RawSyncKey) -> PayloadCodec,
    // Hosts the auto-retry loop that re-runs sync after transient failures.
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val historyStore: DayHistoryStore? = null,
    private val focusContributionStore: FocusContributionStore? = null,
) {
    private val _status = MutableStateFlow(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /**
     * True when the last sync stopped on a first-sync reconciliation choice and the user
     * has not resolved it yet. The UI observes this to show the merge/replace dialog.
     */
    private val _firstSyncChoicePending = MutableStateFlow(false)
    val firstSyncChoicePending: StateFlow<Boolean> = _firstSyncChoicePending.asStateFlow()

    private val mutex = Mutex()

    // Auto-retry state. Only mutated inside runOnce (which always holds [mutex]),
    // so no extra synchronization is needed.
    private var retryJob: Job? = null
    private var retryAttempt = 0

    /**
     * Runs a sync and returns the resulting [SyncStatus], read while still holding [mutex]
     * so a concurrently queued sync cannot overwrite it before the caller observes it.
     */
    suspend fun syncNow(): SyncStatus = mutex.withLock {
        runOnce()
        _status.value
    }

    /**
     * Resolves a pending first-sync choice by re-running the sync with an explicit
     * [strategy]. Returns the resulting status, read under [mutex] like [syncNow].
     */
    suspend fun resolveFirstSync(strategy: FirstSyncStrategy): SyncStatus = mutex.withLock {
        runOnce(strategy)
        _status.value
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
                // A pending first-sync choice means auth + key succeeded; the device is
                // connected. The choice is surfaced separately via [firstSyncChoicePending].
                SyncStatus.Ok, SyncStatus.NeedsChoice -> SyncSetupResult.Success
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

    private suspend fun runOnce(strategy: FirstSyncStrategy? = null) {
        val key = keyStore.loadKey()
        val config = keyStore.loadConfig()
        if (key == null || config == null) {
            _status.value = SyncStatus.NotConfigured
            updateRetrySchedule(result = null)
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
        val result = engine.sync(local, state, now(), strategy)
        _firstSyncChoicePending.value = result is SyncResult.FirstSyncChoiceNeeded
        _status.value =
            when (result) {
                is SyncResult.Applied -> {
                    preferences.persist(result.snapshot)
                    statePersistence.save(result.state)
                    SyncStatus.Ok
                }
                SyncResult.UpToDate -> SyncStatus.Ok
                SyncResult.KeyError -> SyncStatus.KeyError
                SyncResult.FirstSyncChoiceNeeded -> SyncStatus.NeedsChoice
                is SyncResult.Failed -> SyncStatus.Failed
            }
        updateRetrySchedule(result)
    }

    /**
     * Schedules or clears the automatic retry after a sync run. A [SyncResult.Failed]
     * with any cause other than [SyncAuthenticationException] is treated as transient
     * (network down, server error, exhausted push-conflict retries) and schedules a
     * retry; every other outcome cancels the pending retry and resets the backoff —
     * retrying a bad token or an unresolved first-sync choice cannot help. Runs only
     * from [runOnce] (under [mutex]). When the currently running sync *is* the retry
     * job, it is never self-cancelled — only replaced or cleared.
     */
    private suspend fun updateRetrySchedule(result: SyncResult?) {
        val retryable = result is SyncResult.Failed && result.cause !is SyncAuthenticationException
        val previous = retryJob
        if (previous != null && previous !== coroutineContext[Job]) previous.cancel()
        if (retryable) {
            val backoff = 15.seconds
            retryAttempt++
            retryJob = scope.launch {
                delay(backoff)
                syncNow()
            }
        } else {
            retryAttempt = 0
            retryJob = null
        }
    }
}
