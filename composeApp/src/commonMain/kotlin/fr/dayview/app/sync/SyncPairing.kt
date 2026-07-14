package fr.dayview.app.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val PAIRING_PREFIX = "dayview-sync:"
private const val PAIRING_VERSION = 1

@Serializable
private data class PairingWirePayload(
    val version: Int,
    val baseUrl: String,
    val userId: String,
    val key: String,
    val enrollmentCode: String,
    val expiresAtEpochSeconds: Long,
)

data class SyncPairingPayload(
    val baseUrl: String,
    val userId: String,
    val key: RawSyncKey,
    val enrollmentCode: String,
    val expiresAtEpochSeconds: Long,
)

@Serializable
private data class CreatePairingRequest(val userId: String)

@Serializable
private data class CreatePairingResponse(val code: String, val expiresAtEpochSeconds: Long)

@Serializable
private data class ClaimPairingRequest(val code: String)

@Serializable
private data class ClaimPairingResponse(val userId: String, val token: String)

data class SyncPairingTicket(val code: String, val expiresAtEpochSeconds: Long)

data class SyncPairingCode(val content: String, val expiresAtEpochSeconds: Long)

enum class SyncPairingImportResult { Success, InvalidCode, Expired, ConnectionFailed }

sealed interface PairingClaimResult {
    data class Success(val userId: String, val token: String) : PairingClaimResult

    data object Expired : PairingClaimResult

    data object Failed : PairingClaimResult
}

@OptIn(ExperimentalEncodingApi::class)
fun SyncPairingPayload.encode(): String {
    val wire = PairingWirePayload(
        version = PAIRING_VERSION,
        baseUrl = baseUrl.trimEnd('/'),
        userId = userId,
        key = Base64.encode(key.bytes),
        enrollmentCode = enrollmentCode,
        expiresAtEpochSeconds = expiresAtEpochSeconds,
    )
    return PAIRING_PREFIX + SyncJson.encodeToString(wire)
}

@OptIn(ExperimentalEncodingApi::class)
fun decodeSyncPairingPayload(text: String): SyncPairingPayload? = runCatching {
    if (!text.startsWith(PAIRING_PREFIX)) return null
    val wire = SyncJson.decodeFromString<PairingWirePayload>(text.removePrefix(PAIRING_PREFIX))
    if (wire.version != PAIRING_VERSION) return null
    if (!wire.baseUrl.startsWith("https://") && !wire.baseUrl.startsWith("http://")) return null
    if (wire.userId.isBlank() || wire.enrollmentCode.isBlank() || wire.expiresAtEpochSeconds <= 0L) return null
    SyncPairingPayload(
        baseUrl = wire.baseUrl.trimEnd('/'),
        userId = wire.userId,
        key = RawSyncKey(Base64.decode(wire.key)),
        enrollmentCode = wire.enrollmentCode,
        expiresAtEpochSeconds = wire.expiresAtEpochSeconds,
    )
}.getOrNull()

class SyncPairingClient(private val client: HttpClient) {
    suspend fun create(config: SyncConfig): SyncPairingTicket? {
        val response = client.post("${config.baseUrl.trimEnd('/')}/pairing") {
            bearerAuth(config.token)
            contentType(ContentType.Application.Json)
            setBody(CreatePairingRequest(config.userId))
        }
        if (response.status != HttpStatusCode.Created) return null
        val body: CreatePairingResponse = response.body()
        return SyncPairingTicket(body.code, body.expiresAtEpochSeconds)
    }

    suspend fun claim(baseUrl: String, code: String): PairingClaimResult {
        val response = client.post("${baseUrl.trimEnd('/')}/pairing/claim") {
            contentType(ContentType.Application.Json)
            setBody(ClaimPairingRequest(code))
        }
        return when (response.status) {
            HttpStatusCode.OK -> {
                val body: ClaimPairingResponse = response.body()
                PairingClaimResult.Success(body.userId, body.token)
            }

            HttpStatusCode.Gone -> PairingClaimResult.Expired
            else -> PairingClaimResult.Failed
        }
    }
}
