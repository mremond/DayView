package fr.dayview.app.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncDocumentJsonTest {
    @Test
    fun roundTripsThroughJson() {
        val doc = sampleDocument()
        assertEquals(doc, decodeSyncDocument(doc.encodeToString()))
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
        recentDetourMotifs = listOf(SyncItem("coffee", "coffee", false, s)),
        cleanSessions = Versioned(CleanDto(19000, 2, 5, 18999), s),
    )
}
