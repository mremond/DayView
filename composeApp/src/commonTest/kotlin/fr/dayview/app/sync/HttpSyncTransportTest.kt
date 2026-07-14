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
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpSyncTransportTest {
    private fun transport(engine: MockEngine, baseUrl: String = "https://sync.example") = HttpSyncTransport(
        client = HttpClient(engine) { install(ContentNegotiation) { json(SyncJson) } },
        baseUrl = baseUrl,
        userId = "u1",
        token = "tok",
    )

    @Test
    fun pullReturnsNullOn204() = runTest {
        val t = transport(MockEngine { respond("", HttpStatusCode.NoContent) })
        assertNull(t.pull())
    }

    @Test
    fun pullParsesBodyAndRevision() = runTest {
        val t = transport(
            MockEngine {
                respond(
                    """{"revision":"r7","payload":"blob"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val snap = t.pull()
        assertEquals(RemoteSnapshot("blob", "r7"), snap)
    }

    @Test
    fun pushAppliedOn200() = runTest {
        val t = transport(
            MockEngine {
                respond(
                    """{"revision":"r8"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        assertEquals(PushOutcome.Applied("r8"), t.push("blob", expectedRevision = "r7"))
    }

    @Test
    fun pushRejectedOn412ReturnsCurrent() = runTest {
        val t = transport(
            MockEngine {
                respond(
                    """{"revision":"r9","payload":"newer"}""",
                    HttpStatusCode.PreconditionFailed,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val outcome = t.push("blob", expectedRevision = "r7")
        assertTrue(outcome is PushOutcome.Rejected)
        assertEquals(RemoteSnapshot("newer", "r9"), (outcome as PushOutcome.Rejected).current)
    }

    @Test
    fun pushWithNullRevisionSendsIfNoneMatch() = runTest {
        val t = transport(
            MockEngine { request ->
                assertEquals("*", request.headers[HttpHeaders.IfNoneMatch])
                assertNull(request.headers[HttpHeaders.IfMatch])
                respond(
                    """{"revision":"r1"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        assertEquals(PushOutcome.Applied("r1"), t.push("blob", expectedRevision = null))
    }

    @Test
    fun trailingSlashInBaseUrlDoesNotProduceDoubleSlash() = runTest {
        var capturedPath: String? = null
        val t = transport(
            MockEngine { request ->
                capturedPath = request.url.encodedPath
                respond(
                    "",
                    HttpStatusCode.NoContent,
                )
            },
            baseUrl = "https://sync.example/",
        )
        t.pull()
        assertEquals("/sync/u1", capturedPath)
        assertTrue(capturedPath?.contains("//") != true)
    }

    @Test
    fun pushWithRevisionSendsIfMatch() = runTest {
        val t = transport(
            MockEngine { request ->
                assertEquals("r7", request.headers[HttpHeaders.IfMatch])
                assertNull(request.headers[HttpHeaders.IfNoneMatch])
                respond(
                    """{"revision":"r8"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        assertEquals(PushOutcome.Applied("r8"), t.push("blob", expectedRevision = "r7"))
    }

    @Test
    fun getHistoryDayReturnsNullOn204() = runTest {
        val t = transport(MockEngine { respond("", HttpStatusCode.NoContent) })
        assertNull(t.getHistoryDay("abc"))
    }

    @Test
    fun getHistoryDayParsesPayload() = runTest {
        val t = transport(
            MockEngine {
                respond(
                    """{"payload":"blob"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        assertEquals("blob", t.getHistoryDay("abc"))
    }

    @Test
    fun putHistoryDaySendsIfNoneMatchToHistoryPath() = runTest {
        val t = transport(
            MockEngine { request ->
                assertEquals("*", request.headers[HttpHeaders.IfNoneMatch])
                assertTrue(request.url.encodedPath.endsWith("/sync/u1/history/abc"))
                respond("", HttpStatusCode.Created)
            },
        )
        t.putHistoryDay("abc", "blob") // must not throw on 201
    }

    @Test
    fun putHistoryDayTreats412AsSuccess() = runTest {
        val t = transport(MockEngine { respond("", HttpStatusCode.PreconditionFailed) })
        t.putHistoryDay("abc", "blob") // write-once conflict is not an error
    }
}
