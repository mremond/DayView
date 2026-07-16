package fr.dayview.app

/** One device's focus intervals for one day — the unit that syncs on the side channel. */
data class FocusContribution(
    val dayKey: Long,
    val deviceId: String,
    val presence: List<FocusPresenceInterval>,
    val session: List<FocusPresenceInterval>,
    val records: List<FocusSessionRecord> = emptyList(),
)

/**
 * Local store of focus contributions, keyed by (dayKey, deviceId). Holds this device's own
 * archived contributions plus any downloaded from other devices. Unlike [DayHistoryStore] this
 * is not write-once: re-writing the same (day, device) key replaces it (idempotent for sync).
 */
interface FocusContributionStore {
    suspend fun write(contribution: FocusContribution)
    suspend fun read(dayKey: Long, deviceId: String): FocusContribution?
    suspend fun listForDay(dayKey: Long): List<FocusContribution>
    suspend fun listKeys(): List<Pair<Long, String>>
}

class InMemoryFocusContributionStore : FocusContributionStore {
    private val byKey = mutableMapOf<Pair<Long, String>, FocusContribution>()

    override suspend fun write(contribution: FocusContribution) {
        byKey[contribution.dayKey to contribution.deviceId] = contribution
    }

    override suspend fun read(dayKey: Long, deviceId: String): FocusContribution? = byKey[dayKey to deviceId]

    override suspend fun listForDay(dayKey: Long): List<FocusContribution> = byKey.values.filter { it.dayKey == dayKey }

    override suspend fun listKeys(): List<Pair<Long, String>> = byKey.keys.toList()
}

/**
 * Display record whose focus intervals are the coalesced union of the record's own intervals
 * and every contribution for that day. Legacy days / no-sync setups (no contributions) render
 * unchanged, since the union of the record with an empty set is the record itself. Session
 * records are concatenated rather than coalesced, since per-device session windows are disjoint.
 */
fun DayHistoryRecord.withMergedFocus(contributions: List<FocusContribution>): DayHistoryRecord = copy(
    focusPresenceIntervals = mergeIntervals(focusPresenceIntervals + contributions.flatMap { it.presence }),
    focusSessionIntervals = mergeIntervals(focusSessionIntervals + contributions.flatMap { it.session }),
    focusSessionRecords = (focusSessionRecords + contributions.flatMap { it.records }).sortedBy { it.start },
)
