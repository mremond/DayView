package fr.dayview.app.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncDocumentJsonTest {
    @Test
    fun roundTripsThroughJson() {
        val doc = sampleDocument()
        assertEquals(doc, decodeSyncDocument(doc.encodeToString()))
    }

    @Test
    fun completedObligationsRoundTripThroughJson() {
        val doc = sampleDocument()
        assertEquals(
            doc.plannedObligationsCompleted,
            decodeSyncDocument(doc.encodeToString()).plannedObligationsCompleted,
        )
    }

    @Test
    fun decodesLegacyDocumentWithoutCompletedObligations() {
        val full = SyncJson.parseToJsonElement(sampleDocument().encodeToString()).jsonObject
        val legacy = JsonObject(full - "plannedObligationsCompleted")
        val decoded = decodeSyncDocument(SyncJson.encodeToString(JsonObject.serializer(), legacy))
        assertEquals(emptyList(), decoded.plannedObligationsCompleted.items)
        assertEquals(-1L, decoded.plannedObligationsCompleted.dayKey)
    }

    @Test
    fun documentWithoutHistoryDaysDecodesToEmpty() {
        val doc = sampleDocument(deviceId = "a", at = 10)
        val json = doc.copy(historyDays = emptyList()).encodeToString()
        // strip the field to simulate an older client's payload
        val legacy = json.replace(Regex(""","historyDays":\[[^]]*]"""), "")
        assertEquals(emptyList(), decodeSyncDocument(legacy).historyDays)
    }
}

/** Shared fixture reused by later tests. */
fun sampleDocument(deviceId: String = "dev-a", at: Long = 1000): SyncDocument {
    val s = Stamp(at, deviceId)
    return SyncDocument(
        schemaVersion = SYNC_SCHEMA_VERSION,
        dayWindow = Versioned(DayWindow(480, 1080), s),
        showSeconds = Versioned(true, s),
        sound = Versioned(SoundDto(false, true, true, true, 30, 40), s),
        goal = Versioned(GoalDto("Ship", 2000L, 1500L), s),
        pomodoro = Versioned(PomodoroDto(25, -1L), s),
        focusIntention = Versioned("write", s),
        themeMode = Versioned("SYSTEM", s),
        netTimeEnabled = Versioned(false, s),
        detours = DayScoped(19000, listOf(SyncItem("k1", DetourDto(10, 20, "coffee"), false, s))),
        plannedObligations = DayScoped(19000, listOf(SyncItem("call", "call", false, s))),
        plannedObligationsCompleted = DayScoped(19000, listOf(SyncItem("done", "done", false, s))),
        recentDetourMotifs = listOf(SyncItem("coffee", "coffee", false, s)),
        cleanSessions = Versioned(CleanDto(19000, 2, 5, 18999), s),
    )
}
