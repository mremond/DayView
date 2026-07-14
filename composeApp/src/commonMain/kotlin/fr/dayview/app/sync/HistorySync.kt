package fr.dayview.app.sync

import fr.dayview.app.DayHistoryStore
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reconciles the local day-history archive with the server's write-once per-day blobs.
 * Runs after the main-document merge: [reconcile] receives the merged manifest (base ∪
 * remote historyDays), downloads days it lacks locally, uploads local-only days (bounded
 * per cycle), and returns the manifest to stamp into the pushed document — the merged
 * manifest plus days this cycle confirmed on the server.
 */
class HistorySync(
    private val store: DayHistoryStore,
    private val transport: SyncTransport,
    private val blobCodec: HistoryBlobCodec,
    private val keyIndex: HistoryKey,
    private val maxPerCycle: Int = 50,
) {
    suspend fun reconcile(knownManifest: List<Long>): List<Long> {
        val known = knownManifest.toSet()
        val local = store.listAllDays().toSet()

        // Download manifest days we don't have; failures leave the day in the manifest for retry.
        for (day in (known - local).sorted().take(maxPerCycle)) {
            try {
                val blob = transport.getHistoryDay(keyIndex.opaqueKey(day)) ?: continue
                val record = HistoryRecordMapper.deserialize(blobCodec.decrypt(day, blob)) ?: continue
                store.write(record)
            } catch (e: SyncKeyMismatchException) {
                // wrong key for this blob — skip; a device with the right key will fill it
            } catch (e: CancellationException) {
                throw e // never swallow cancellation — unwind structured concurrency
            } catch (e: Throwable) {
                // transient/network — retried next cycle
            }
        }

        // Upload local days the archive doesn't know yet; only confirmed uploads enter the manifest.
        val uploaded = mutableSetOf<Long>()
        for (day in (local - known).sorted().take(maxPerCycle)) {
            val record = store.read(day) ?: continue
            try {
                val blob = blobCodec.encrypt(day, HistoryRecordMapper.serialize(record))
                transport.putHistoryDay(keyIndex.opaqueKey(day), blob)
                uploaded += day // Created or 412-already-present both mean "on the server"
            } catch (e: CancellationException) {
                throw e // never swallow cancellation — unwind structured concurrency
            } catch (e: Throwable) {
                // transient/network — not added to the manifest, retried next cycle
            }
        }

        return (known + uploaded).sorted()
    }
}
