package fr.dayview.app

import fr.dayview.app.sync.applyDocument
import fr.dayview.app.sync.buildDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class SyncMapperOpenDetourTest {
    @Test
    fun openDetourSurvivesBuildAndApplyRoundTrip() {
        val snapshot = DayPreferencesSnapshot(
            openDetourStart = Instant.fromEpochMilliseconds(789L),
            openDetourCategory = "Réunion",
            openDetourDescription = "point équipe",
        )
        val document = buildDocument(snapshot, base = null, deviceId = "device-a", now = 1_000L)
        val restored = applyDocument(document, DayPreferencesSnapshot())
        assertEquals(Instant.fromEpochMilliseconds(789L), restored.openDetourStart)
        assertEquals("Réunion", restored.openDetourCategory)
        assertEquals("point équipe", restored.openDetourDescription)
    }

    @Test
    fun nullOpenDetourStartRoundTripsAsNull() {
        val document = buildDocument(DayPreferencesSnapshot(), base = null, deviceId = "device-a", now = 1_000L)
        val restored = applyDocument(document, DayPreferencesSnapshot())
        assertNull(restored.openDetourStart)
    }
}
