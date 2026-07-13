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
        val stored = SyncJson.decodeFromString<StoredState>(text)
        return SyncState(stored.baseRevision, stored.baseDocument)
    }

    override suspend fun save(state: SyncState) {
        write(SyncJson.encodeToString(StoredState(state.baseRevision, state.baseDocument)))
    }
}
