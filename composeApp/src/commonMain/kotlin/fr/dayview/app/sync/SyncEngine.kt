package fr.dayview.app.sync

import fr.dayview.app.DayPreferencesSnapshot
import kotlin.coroutines.cancellation.CancellationException

data class SyncState(val baseRevision: String?, val baseDocument: SyncDocument?)

/**
 * How a device's *first* sync should reconcile with a server that already holds a document.
 * Only consulted when this device has never synced before ([SyncState.baseDocument] == null)
 * and the server is non-empty; otherwise the ordinary last-writer-wins merge runs unconditionally.
 */
enum class FirstSyncStrategy {
    /** Union lists and resolve scalars by last-write-wins (the ordinary merge). */
    Merge,

    /** Force-pull: adopt the server's document verbatim, discarding local values. */
    AdoptServer,

    /** Force-push: overwrite the server with this device's document. */
    PushLocal,
}

sealed interface SyncResult {
    data object UpToDate : SyncResult
    data class Applied(val snapshot: DayPreferencesSnapshot, val state: SyncState) : SyncResult
    data object KeyError : SyncResult

    /**
     * This device has never synced before and the server already holds a document, so the
     * engine stops rather than silently merging (local scalars, freshly stamped with *now*,
     * would otherwise win over existing server values). The caller must re-run with an explicit
     * [FirstSyncStrategy].
     */
    data object FirstSyncChoiceNeeded : SyncResult
    data class Failed(val cause: Throwable) : SyncResult
}

class SyncEngine(
    private val transport: SyncTransport,
    private val codec: PayloadCodec,
    private val deviceId: String,
    private val maxRetries: Int = 3,
    private val historySync: HistorySync? = null,
    private val focusSync: FocusContributionSync? = null,
) {
    suspend fun sync(
        local: DayPreferencesSnapshot,
        state: SyncState,
        now: Long,
        strategy: FirstSyncStrategy? = null,
    ): SyncResult {
        val localDoc = buildDocument(local, state.baseDocument, deviceId, now)
        val neverSynced = state.baseDocument == null
        try {
            repeat(maxRetries) { attempt ->
                val remote = transport.pull()
                val remoteDoc = remote?.let {
                    try {
                        decodeSyncDocument(codec.decrypt(it.payload))
                    } catch (e: SyncKeyMismatchException) {
                        return SyncResult.KeyError
                    }
                }
                // First-sync reconciliation: decided by the *initial* pull only. A non-null remote
                // that first appears on a later retry is a concurrent writer to merge against, not a
                // pre-existing server the user must choose how to adopt.
                if (attempt == 0 && neverSynced && remoteDoc != null) {
                    when (strategy) {
                        null -> return SyncResult.FirstSyncChoiceNeeded
                        FirstSyncStrategy.AdoptServer ->
                            return SyncResult.Applied(
                                snapshot = applyDocument(remoteDoc, local),
                                state = SyncState(remote.revision, remoteDoc),
                            )
                        FirstSyncStrategy.PushLocal -> {
                            val payload = codec.encrypt(localDoc.encodeToString())
                            when (val outcome = transport.push(payload, remote.revision)) {
                                is PushOutcome.Applied ->
                                    return SyncResult.Applied(
                                        snapshot = applyDocument(localDoc, local),
                                        state = SyncState(outcome.revision, localDoc),
                                    )
                                is PushOutcome.Rejected -> return@repeat
                            }
                        }
                        FirstSyncStrategy.Merge -> Unit // fall through to the ordinary merge
                    }
                }
                var merged = localDoc.merge(remoteDoc)
                if (historySync != null) {
                    merged = merged.copy(historyDays = historySync.reconcile(merged.historyDays))
                }
                if (focusSync != null) {
                    merged = merged.copy(focusContributions = focusSync.reconcile(merged.focusContributions))
                }
                if (remoteDoc != null && merged == remoteDoc) {
                    // The server already holds the merged document, so there is nothing to push.
                    // But local preferences may still be behind it — e.g. another device set the
                    // goal and this device has no local change of its own to trigger a push. Adopt
                    // the document in that case instead of reporting UpToDate and dropping the
                    // remote change; only skip the write when local already matches.
                    val applied = applyDocument(merged, local)
                    return if (applied == local) {
                        SyncResult.UpToDate
                    } else {
                        SyncResult.Applied(applied, SyncState(remote?.revision, merged))
                    }
                }
                val payload = codec.encrypt(merged.encodeToString())
                when (val outcome = transport.push(payload, remote?.revision)) {
                    is PushOutcome.Applied ->
                        return SyncResult.Applied(
                            snapshot = applyDocument(merged, local),
                            state = SyncState(outcome.revision, merged),
                        )
                    is PushOutcome.Rejected -> Unit
                }
            }
        } catch (e: SyncKeyMismatchException) {
            return SyncResult.KeyError
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return SyncResult.Failed(e)
        }
        return SyncResult.Failed(IllegalStateException("Exhausted $maxRetries sync retries"))
    }
}
