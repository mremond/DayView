package fr.dayview.app.sync

import fr.dayview.app.FocusContributionStore
import kotlin.coroutines.cancellation.CancellationException

/**
 * Per-device focus side channel. Uploads this device's own contributions and downloads
 * others', keeping the write-once history record untouched. Entries are "dayKey:deviceId".
 */
class FocusContributionSync(
    private val store: FocusContributionStore,
    private val transport: SyncTransport,
    private val blobCodec: HistoryBlobCodec,
    private val keyIndex: HistoryKey,
    private val deviceId: String,
    private val maxPerCycle: Int = 50,
) {
    private fun entry(dayKey: Long, device: String) = "$dayKey:$device"

    private fun parse(entry: String): Pair<Long, String>? {
        val i = entry.indexOf(':')
        if (i <= 0) return null
        val day = entry.substring(0, i).toLongOrNull() ?: return null
        return day to entry.substring(i + 1)
    }

    suspend fun reconcile(knownManifest: List<String>): List<String> {
        val known = knownManifest.toSet()

        // Download foreign entries we don't yet have locally.
        for (item in knownManifest.mapNotNull(::parse).filter { it.second != deviceId }.sortedBy { it.first }.take(maxPerCycle)) {
            val (day, device) = item
            if (store.read(day, device) != null) continue
            try {
                val blob = transport.getHistoryDay(keyIndex.opaqueFocusKey(day, device)) ?: continue
                val contribution = FocusContributionMapper.deserialize(blobCodec.decryptFocus(day, device, blob)) ?: continue
                if (contribution.dayKey != day || contribution.deviceId != device) continue
                store.write(contribution)
            } catch (e: SyncKeyMismatchException) {
                // wrong key for this blob — skip
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // transient — retried next cycle
            }
        }

        // Upload our own contributions the manifest doesn't know yet.
        val uploaded = mutableSetOf<String>()
        val ownKeys = store.listKeys().filter { it.second == deviceId }.map { entry(it.first, it.second) }
        for (e in (ownKeys.toSet() - known).sorted().take(maxPerCycle)) {
            val (day, device) = parse(e) ?: continue
            val contribution = store.read(day, device) ?: continue
            try {
                val blob = blobCodec.encryptFocus(day, device, FocusContributionMapper.serialize(contribution))
                transport.putHistoryDay(keyIndex.opaqueFocusKey(day, device), blob)
                uploaded += e
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Throwable) {
                // transient — not added to the manifest, retried next cycle
            }
        }

        return (known + uploaded).sorted()
    }
}
