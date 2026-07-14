package fr.dayview.app.sync

import fr.dayview.app.DayPreferencesSnapshot
import kotlin.coroutines.cancellation.CancellationException

data class SyncState(val baseRevision: String?, val baseDocument: SyncDocument?)

sealed interface SyncResult {
    data object UpToDate : SyncResult
    data class Applied(val snapshot: DayPreferencesSnapshot, val state: SyncState) : SyncResult
    data object KeyError : SyncResult
    data class Failed(val cause: Throwable) : SyncResult
}

class SyncEngine(
    private val transport: SyncTransport,
    private val codec: PayloadCodec,
    private val deviceId: String,
    private val maxRetries: Int = 3,
    private val historySync: HistorySync? = null,
) {
    suspend fun sync(local: DayPreferencesSnapshot, state: SyncState, now: Long): SyncResult {
        val localDoc = buildDocument(local, state.baseDocument, deviceId, now)
        try {
            repeat(maxRetries) {
                val remote = transport.pull()
                val remoteDoc = remote?.let {
                    try {
                        decodeSyncDocument(codec.decrypt(it.payload))
                    } catch (e: SyncKeyMismatchException) {
                        return SyncResult.KeyError
                    }
                }
                var merged = localDoc.merge(remoteDoc)
                if (historySync != null) {
                    merged = merged.copy(historyDays = historySync.reconcile(merged.historyDays))
                }
                if (remoteDoc != null && merged == remoteDoc) return SyncResult.UpToDate
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
