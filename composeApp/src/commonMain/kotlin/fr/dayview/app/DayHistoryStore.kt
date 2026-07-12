package fr.dayview.app

internal interface DayHistoryStore {
    suspend fun write(record: DayHistoryRecord)
    suspend fun read(dayKey: Long): DayHistoryRecord?
    suspend fun listDays(range: LongRange): List<Long>
}

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
}

internal class InMemoryDayHistoryStore : DayHistoryStore {
    private val records = mutableMapOf<Long, DayHistoryRecord>()

    override suspend fun write(record: DayHistoryRecord) {
        records.putIfAbsent(record.dayKey, record)
    }

    override suspend fun read(dayKey: Long): DayHistoryRecord? = records[dayKey]

    override suspend fun listDays(range: LongRange): List<Long> = records.keys.filter { it in range }.sorted()
}

internal fun createDayHistoryStore(): DayHistoryStore = createHistoryFileSystem()?.let { FileDayHistoryStore(it) } ?: InMemoryDayHistoryStore()
