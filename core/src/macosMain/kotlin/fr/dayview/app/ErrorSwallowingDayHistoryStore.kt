package fr.dayview.app

import kotlinx.coroutines.CancellationException

/**
 * Wraps a [DayHistoryStore] so a filesystem failure never reaches the coroutines
 * `DayViewController` launches to archive a day (`scope.launch { history.write(record) }`,
 * with no `CoroutineExceptionHandler`). `OkioHistoryFileSystem` throws on every failure
 * (`createDirectories`, `write`, `atomicMove`), and on Kotlin/Native an unhandled exception in
 * a launched coroutine terminates the whole process — the same reason presence persistence is
 * guarded in `DayViewSession.persistPresenceIfDue` and the initial load is guarded in
 * `DayViewNative.create`. Archiving is best-effort: losing a day's record is bad, crashing on
 * every subsequent launch (the triggering disk state is persistent) is worse. Cancellation
 * must still propagate normally.
 */
internal class ErrorSwallowingDayHistoryStore(
    private val delegate: DayHistoryStore,
) : DayHistoryStore {
    override suspend fun write(record: DayHistoryRecord) {
        try {
            delegate.write(record)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Swallowed intentionally: see class comment.
        }
    }

    override suspend fun read(dayKey: Long): DayHistoryRecord? = try {
        delegate.read(dayKey)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    override suspend fun listDays(range: LongRange): List<Long> = try {
        delegate.listDays(range)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        emptyList()
    }

    override suspend fun listAllDays(): List<Long> = try {
        delegate.listAllDays()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        emptyList()
    }
}
