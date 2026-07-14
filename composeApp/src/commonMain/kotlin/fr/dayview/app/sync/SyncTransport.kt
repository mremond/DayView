package fr.dayview.app.sync

data class RemoteSnapshot(val payload: String, val revision: String)

sealed interface PushOutcome {
    data class Applied(val revision: String) : PushOutcome
    data class Rejected(val current: RemoteSnapshot) : PushOutcome
}

interface SyncTransport {
    suspend fun pull(): RemoteSnapshot?
    suspend fun push(payload: String, expectedRevision: String?): PushOutcome
    suspend fun putHistoryDay(opaqueKey: String, payload: String)
    suspend fun getHistoryDay(opaqueKey: String): String?
}
