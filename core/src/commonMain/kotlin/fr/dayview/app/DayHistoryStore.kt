package fr.dayview.app

/**
 * Platform file access for the history directory. `name` is the bare `dayKey` string.
 * Public rather than internal: `:shared` implements it for the JVM and Android apps.
 */
interface HistoryFileSystem {
    fun read(name: String): String?

    fun writeAtomic(
        name: String,
        text: String,
    )

    fun list(): List<String>
}

interface DayHistoryStore {
    suspend fun write(record: DayHistoryRecord)
    suspend fun read(dayKey: Long): DayHistoryRecord?
    suspend fun listDays(range: LongRange): List<Long>
    suspend fun listAllDays(): List<Long>
}

class InMemoryDayHistoryStore : DayHistoryStore {
    private val records = mutableMapOf<Long, DayHistoryRecord>()

    override suspend fun write(record: DayHistoryRecord) {
        if (!records.containsKey(record.dayKey)) records[record.dayKey] = record
    }

    override suspend fun read(dayKey: Long): DayHistoryRecord? = records[dayKey]

    override suspend fun listDays(range: LongRange): List<Long> = records.keys.filter { it in range }.sorted()

    override suspend fun listAllDays(): List<Long> = records.keys.sorted()
}

/**
 * File-per-day archive over a [HistoryFileSystem]. Writing is idempotent — an existing day is
 * never clobbered — because a stale day may be archived more than once (a cold launch and a
 * rollover tick can both reach it).
 */
class FileDayHistoryStore(private val fs: HistoryFileSystem) : DayHistoryStore {
    override suspend fun write(record: DayHistoryRecord) {
        val name = record.dayKey.toString()
        if (fs.read(name) != null) return
        fs.writeAtomic(name, DayHistoryCodec.encode(record))
    }

    override suspend fun read(dayKey: Long): DayHistoryRecord? = fs.read(dayKey.toString())?.let { DayHistoryCodec.decode(it) }

    override suspend fun listDays(range: LongRange): List<Long> = fs.list().mapNotNull { it.toLongOrNull() }.filter { it in range }.sorted()

    override suspend fun listAllDays(): List<Long> = fs.list().mapNotNull { it.toLongOrNull() }.sorted()
}
