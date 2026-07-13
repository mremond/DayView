package fr.dayview.app.sync

import kotlinx.serialization.Serializable

interface SyncStatePersistence {
    suspend fun load(): SyncState
    suspend fun save(state: SyncState)
}

@Serializable
private data class StoredState(val baseRevision: String? = null, val baseDocument: SyncDocument? = null)

class FileSyncStatePersistence(
    private val read: suspend () -> String?,
    private val write: suspend (String) -> Unit,
) : SyncStatePersistence {
    override suspend fun load(): SyncState {
        val text = read() ?: return SyncState(null, null)
        // A truncated, hand-edited, or schema-changed file must degrade to "nothing stored"
        // rather than crash the caller (e.g. SyncCoordinator.runOnce(), ahead of the sync
        // engine's own try/catch).
        val stored = runCatching { SyncJson.decodeFromString<StoredState>(text) }.getOrNull()
            ?: return SyncState(null, null)
        return SyncState(stored.baseRevision, stored.baseDocument)
    }

    override suspend fun save(state: SyncState) {
        write(SyncJson.encodeToString(StoredState(state.baseRevision, state.baseDocument)))
    }
}
