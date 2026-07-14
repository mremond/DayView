package fr.dayview.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class OpenDetourTest {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun controller(snapshot: DayPreferencesSnapshot) = DayViewController(
        DefaultDayPreferences,
        CoroutineScope(Dispatchers.Unconfined),
        initialSnapshot = snapshot,
        initialNow = now,
    )

    @Test
    fun startBeginsRunningSessionWithSanitizedCategory() {
        val c = controller(DayPreferencesSnapshot())
        c.startOpenDetour("  Café\nen bas ", "discussion")
        assertEquals(now, c.state.openDetourStart)
        assertTrue(c.state.openDetourRunning)
        assertEquals("Café en bas", c.state.openDetourCategory)
        assertEquals("discussion", c.state.openDetourDescription)
    }

    @Test
    fun startIgnoresBlankCategory() {
        val c = controller(DayPreferencesSnapshot())
        c.startOpenDetour("   ", "x")
        assertNull(c.state.openDetourStart)
    }

    @Test
    fun startRefusedWhileFocusRunning() {
        val c = controller(DayPreferencesSnapshot(pomodoroEnd = now + 25.minutes))
        c.startOpenDetour("Réunion", "")
        assertNull(c.state.openDetourStart)
    }

    @Test
    fun startPomodoroRefusedWhileOpenDetourRunning() {
        val c = controller(DayPreferencesSnapshot(openDetourStart = now, focusIntention = "Écrire"))
        c.startPomodoro()
        assertFalse(c.state.focusIsActive)
    }

    @Test
    fun stopRecordsEpisodeAndClears() {
        val c = controller(
            DayPreferencesSnapshot(openDetourStart = now - 15.minutes, openDetourCategory = "Réunion"),
        )
        c.stopOpenDetour()
        assertNull(c.state.openDetourStart)
        assertEquals("", c.state.openDetourCategory)
        val episode = c.state.detoursToday.single()
        assertEquals("Réunion", episode.category)
        assertEquals(now, episode.end)
        assertEquals(15.minutes, episode.end - episode.start)
    }

    @Test
    fun elapsedCountsUpFromStart() {
        val c = controller(DayPreferencesSnapshot(openDetourStart = now - 3.minutes))
        assertEquals(3.minutes, c.state.openDetourElapsed)
    }
}
