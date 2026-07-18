package fr.dayview.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
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
    fun startAcceptsBlankCategory() {
        val c = controller(DayPreferencesSnapshot())
        c.startOpenDetour()
        assertEquals(now, c.state.openDetourStart)
        assertEquals("", c.state.openDetourCategory)
    }

    @Test
    fun startAllowedWhileFocusRunning() {
        val c = controller(DayPreferencesSnapshot(pomodoroEnd = now + 25.minutes))
        c.startOpenDetour()
        assertEquals(now, c.state.openDetourStart)
        assertTrue(c.state.focusIsActive)
    }

    @Test
    fun startPomodoroRefusedWhileOpenDetourRunning() {
        val c = controller(DayPreferencesSnapshot(openDetourStart = now, focusIntention = "Écrire"))
        c.startPomodoro()
        assertFalse(c.state.focusIsActive)
    }

    @Test
    fun stopRecordsEpisodeWithTheMotifGivenAtStop() {
        // Midday local: 12 h of margin on both sides so the day floor can never dominate.
        val midday = startOfLocalDay(now) + 12.hours
        val c = DayViewController(
            DefaultDayPreferences,
            CoroutineScope(Dispatchers.Unconfined),
            initialSnapshot = DayPreferencesSnapshot(openDetourStart = midday - 15.minutes),
            initialNow = midday,
        )
        c.stopOpenDetour("  Réunion\nimprévue ", "avec Paul")
        assertNull(c.state.openDetourStart)
        assertEquals("", c.state.openDetourCategory)
        val episode = c.state.detoursToday.single()
        assertEquals("Réunion imprévue", episode.category)
        assertEquals("avec Paul", episode.description)
        assertEquals(midday, episode.end)
        assertEquals(15.minutes, episode.end - episode.start)
    }

    @Test
    fun stopIgnoresABlankMotif() {
        val c = controller(DayPreferencesSnapshot(openDetourStart = now - 15.minutes))
        c.stopOpenDetour("   ")
        assertEquals(now - 15.minutes, c.state.openDetourStart)
        assertTrue(c.state.detoursToday.isEmpty())
    }

    @Test
    fun stopCapsTheSpanAtFourHours() {
        // Midday local: 12 h of margin on both sides so the day floor can never dominate.
        val midday = startOfLocalDay(now) + 12.hours
        val c = DayViewController(
            DefaultDayPreferences,
            CoroutineScope(Dispatchers.Unconfined),
            initialSnapshot = DayPreferencesSnapshot(openDetourStart = midday - 9.hours),
            initialNow = midday,
        )
        c.stopOpenDetour("Oubli")
        val episode = c.state.detoursToday.single()
        assertEquals(midday, episode.end)
        assertEquals(4.hours, episode.end - episode.start)
    }

    @Test
    fun stopNeverCrossesMidnight() {
        // 01:00 local: the 4 h cap would still reach into yesterday, so the day floor wins.
        val startOfToday = startOfLocalDay(now)
        val oneAm = startOfToday + 1.hours
        val c = DayViewController(
            DefaultDayPreferences,
            CoroutineScope(Dispatchers.Unconfined),
            initialSnapshot = DayPreferencesSnapshot(openDetourStart = oneAm - 3.hours),
            initialNow = oneAm,
        )
        c.stopOpenDetour("Nuit blanche")
        val episode = c.state.detoursToday.single()
        assertEquals(startOfToday, episode.start)
        assertEquals(oneAm, episode.end)
    }

    @Test
    fun cancelClearsWithoutRecording() {
        val c = controller(DayPreferencesSnapshot(openDetourStart = now - 15.minutes))
        c.cancelOpenDetour()
        assertNull(c.state.openDetourStart)
        assertEquals("", c.state.openDetourCategory)
        assertTrue(c.state.detoursToday.isEmpty())
    }

    @Test
    fun elapsedCountsUpFromStart() {
        val c = controller(DayPreferencesSnapshot(openDetourStart = now - 3.minutes))
        assertEquals(3.minutes, c.state.openDetourElapsed)
    }

    @Test
    fun startIgnoredWhileOpenDetourAlreadyRunning() {
        val c = controller(DayPreferencesSnapshot(openDetourStart = now, openDetourCategory = "Première"))
        c.tick(now + 2.minutes)
        c.startOpenDetour("Deuxième", "x")
        assertEquals(now, c.state.openDetourStart)
        assertEquals("Première", c.state.openDetourCategory)
    }
}
