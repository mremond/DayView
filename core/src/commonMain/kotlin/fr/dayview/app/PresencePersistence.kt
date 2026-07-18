package fr.dayview.app

/**
 * Day-scoped presence storage. [dayKey] is -1 when nothing is stored; implementations apply
 * the staleness rule at read time, returning empty lists for a day that is not today, so a
 * stale write can never resurrect yesterday's arcs.
 */
data class StoredPresence(
    val dayKey: Long = -1L,
    val presence: List<FocusPresenceInterval> = emptyList(),
    val session: List<FocusPresenceInterval> = emptyList(),
)

/**
 * Persistence seam for the high-frequency focus-presence lists, which live outside the shared
 * [DayPreferencesSnapshot] (the Compose/JVM app keeps them out of it for the same reason).
 * Injected into [DayViewSession]; the no-op default leaves every other call site in-memory.
 */
interface PresencePersistence {
    suspend fun load(): StoredPresence

    suspend fun save(
        dayKey: Long,
        presence: List<FocusPresenceInterval>,
        session: List<FocusPresenceInterval>,
    )
}

object NoopPresencePersistence : PresencePersistence {
    override suspend fun load(): StoredPresence = StoredPresence()

    override suspend fun save(
        dayKey: Long,
        presence: List<FocusPresenceInterval>,
        session: List<FocusPresenceInterval>,
    ) = Unit
}
