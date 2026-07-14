package fr.dayview.app.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

@Serializable private data class RemoteBody(val revision: String, val payload: String? = null)

@Serializable private data class PushBody(val payload: String)

@Serializable private data class HistoryBody(val payload: String)

class SyncAuthenticationException : Exception("Sync authentication failed")

class HttpSyncTransport(
    private val client: HttpClient,
    private val baseUrl: String,
    private val userId: String,
    private val token: String,
) : SyncTransport {
    private val endpoint get() = "${baseUrl.trimEnd('/')}/sync/$userId"

    override suspend fun pull(): RemoteSnapshot? {
        val response = client.get(endpoint) { bearerAuth(token) }
        if (response.status == HttpStatusCode.NoContent) return null
        if (response.status == HttpStatusCode.Unauthorized) throw SyncAuthenticationException()
        if (response.status != HttpStatusCode.OK) {
            throw IllegalStateException("sync GET failed: ${response.status}")
        }
        val body: RemoteBody = response.body()
        return RemoteSnapshot(payload = body.payload.orEmpty(), revision = body.revision)
    }

    override suspend fun push(payload: String, expectedRevision: String?): PushOutcome {
        val response = client.put(endpoint) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            if (expectedRevision == null) {
                header(HttpHeaders.IfNoneMatch, "*")
            } else {
                header(HttpHeaders.IfMatch, expectedRevision)
            }
            setBody(PushBody(payload))
        }
        return if (response.status == HttpStatusCode.PreconditionFailed) {
            val body: RemoteBody = response.body()
            PushOutcome.Rejected(RemoteSnapshot(body.payload.orEmpty(), body.revision))
        } else {
            PushOutcome.Applied(response.body<RemoteBody>().revision)
        }
    }

    override suspend fun putHistoryDay(opaqueKey: String, payload: String) {
        val response = client.put("$endpoint/history/$opaqueKey") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            header(HttpHeaders.IfNoneMatch, "*")
            setBody(PushBody(payload))
        }
        when (response.status) {
            HttpStatusCode.Created, HttpStatusCode.OK, HttpStatusCode.PreconditionFailed -> Unit
            else -> throw IllegalStateException("history PUT failed: ${response.status}")
        }
    }

    override suspend fun getHistoryDay(opaqueKey: String): String? {
        val response = client.get("$endpoint/history/$opaqueKey") { bearerAuth(token) }
        if (response.status == HttpStatusCode.NoContent) return null
        if (response.status != HttpStatusCode.OK) throw IllegalStateException("history GET failed: ${response.status}")
        return response.body<HistoryBody>().payload
    }
}
