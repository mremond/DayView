package fr.dayview.app

import fr.dayview.app.sync.FocusContributionMapper

/** Platform file access for the history directory. `name` is the bare `dayKey` string. */
internal interface HistoryFileSystem {
    fun read(name: String): String?
    fun writeAtomic(name: String, text: String)
    fun list(): List<String>
}

/** Null when no writable location is available (e.g. Android before the app context is set). */
internal expect fun createHistoryFileSystem(): HistoryFileSystem?

internal class FileDayHistoryStore(private val fs: HistoryFileSystem) : DayHistoryStore {
    override suspend fun write(record: DayHistoryRecord) {
        val name = record.dayKey.toString()
        if (fs.read(name) != null) return // idempotent: never clobber an earlier archive
        fs.writeAtomic(name, DayHistoryCodec.encode(record))
    }

    override suspend fun read(dayKey: Long): DayHistoryRecord? = fs.read(dayKey.toString())?.let { DayHistoryCodec.decode(it) }

    override suspend fun listDays(range: LongRange): List<Long> = fs.list().mapNotNull { it.toLongOrNull() }.filter { it in range }.sorted()

    override suspend fun listAllDays(): List<Long> = fs.list().mapNotNull { it.toLongOrNull() }.sorted()
}

internal fun createDayHistoryStore(): DayHistoryStore = createHistoryFileSystem()?.let { FileDayHistoryStore(it) } ?: InMemoryDayHistoryStore()

/**
 * Focus contributions on the same directory as the history archive, under `focus_<day>_<device>`
 * filenames. [FileDayHistoryStore] ignores these (its keys are bare numeric dayKeys), so the two
 * families coexist in one directory without a second platform filesystem.
 */
internal class FileFocusContributionStore(private val fs: HistoryFileSystem) : FocusContributionStore {
    private fun name(dayKey: Long, deviceId: String) = "focus_${dayKey}_$deviceId"

    override suspend fun write(contribution: FocusContribution) {
        fs.writeAtomic(name(contribution.dayKey, contribution.deviceId), FocusContributionMapper.serialize(contribution))
    }

    override suspend fun read(dayKey: Long, deviceId: String): FocusContribution? = fs.read(name(dayKey, deviceId))?.let { FocusContributionMapper.deserialize(it) }

    override suspend fun listForDay(dayKey: Long): List<FocusContribution> = keys().filter { it.first == dayKey }.mapNotNull { read(it.first, it.second) }

    override suspend fun listKeys(): List<Pair<Long, String>> = keys()

    private fun keys(): List<Pair<Long, String>> = fs.list().mapNotNull { n ->
        if (!n.startsWith("focus_")) return@mapNotNull null
        val rest = n.removePrefix("focus_")
        val i = rest.indexOf('_')
        if (i <= 0) return@mapNotNull null
        val day = rest.substring(0, i).toLongOrNull() ?: return@mapNotNull null
        day to rest.substring(i + 1)
    }
}

internal fun createFocusContributionStore(): FocusContributionStore = createHistoryFileSystem()?.let { FileFocusContributionStore(it) } ?: InMemoryFocusContributionStore()
