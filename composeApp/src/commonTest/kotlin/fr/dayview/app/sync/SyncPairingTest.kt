package fr.dayview.app.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SyncPairingTest {
    @Test
    fun payloadRoundTripsAllSetupFields() {
        val key = RawSyncKey(ByteArray(32) { it.toByte() })
        val encoded = SyncPairingPayload(
            baseUrl = "https://sync.example/",
            userId = "alice",
            key = key,
            enrollmentCode = "one-time-code",
            expiresAtEpochSeconds = 1234L,
        ).encode()

        val decoded = assertNotNull(decodeSyncPairingPayload(encoded))
        assertEquals("https://sync.example", decoded.baseUrl)
        assertEquals("alice", decoded.userId)
        assertContentEquals(key.bytes, decoded.key.bytes)
        assertEquals("one-time-code", decoded.enrollmentCode)
        assertEquals(1234L, decoded.expiresAtEpochSeconds)
    }

    @Test
    fun payloadRejectsForeignOrMalformedCodes() {
        assertNull(decodeSyncPairingPayload("https://sync.example"))
        assertNull(decodeSyncPairingPayload("dayview-sync:{}"))
    }

    @Test
    fun createUsesCurrentCredentialsAndParsesTicket() = runTest {
        val engine = MockEngine { request ->
            assertEquals("Bearer current-token", request.headers[HttpHeaders.Authorization])
            assertEquals("/pairing", request.url.encodedPath)
            respond(
                """{"code":"abc","expiresAtEpochSeconds":42}""",
                HttpStatusCode.Created,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val pairing = SyncPairingClient(client(engine))
            .create(SyncConfig("https://sync.example", "alice", "current-token"))

        assertEquals(SyncPairingTicket("abc", 42L), pairing)
    }

    @Test
    fun claimReturnsDeviceCredential() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/pairing/claim", request.url.encodedPath)
            respond(
                """{"userId":"alice","token":"device-token"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val result = SyncPairingClient(client(engine)).claim("https://sync.example", "abc")
        val success = assertIs<PairingClaimResult.Success>(result)
        assertEquals("alice", success.userId)
        assertEquals("device-token", success.token)
    }

    @Test
    fun expiredClaimIsDistinctFromTransportFailure() = runTest {
        val expired = SyncPairingClient(client(MockEngine { respond("", HttpStatusCode.Gone) }))
            .claim("https://sync.example", "abc")
        val failed = SyncPairingClient(client(MockEngine { respond("", HttpStatusCode.InternalServerError) }))
            .claim("https://sync.example", "abc")

        assertEquals(PairingClaimResult.Expired, expired)
        assertEquals(PairingClaimResult.Failed, failed)
    }

    private fun client(engine: MockEngine) = HttpClient(engine) {
        install(ContentNegotiation) { json(SyncJson) }
    }
}
