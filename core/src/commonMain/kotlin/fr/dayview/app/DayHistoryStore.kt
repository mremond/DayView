package fr.dayview.app

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
