package fr.dayview.app

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class DayPreferencesTest {
    @Test
    fun fallbackPreferencesExposeSafeDefaults() = runTest {
        // DefaultDayPreferences is a process-wide singleton; reset it first so this
        // assertion doesn't depend on whether another test ran (and persisted) before it.
        DefaultDayPreferences.persist(DayPreferencesSnapshot())

        assertEquals(DayPreferencesSnapshot(), DefaultDayPreferences.snapshots.first())
    }

    @Test
    fun fallbackPreferencesRoundTripPersistedSnapshots() = runTest {
        val written = DayPreferencesSnapshot(
            startMinutes = 0,
            endMinutes = 1,
            showSeconds = false,
            soundSettings = SoundSettings(enabled = true),
            goalTitle = "Temporaire",
            goalDeadline = Instant.fromEpochMilliseconds(42L),
            goalStart = Instant.fromEpochMilliseconds(42L),
            pomodoroMinutes = 50,
            pomodoroEnd = Instant.fromEpochMilliseconds(123L),
            focusIntention = "Terminer le test",
            openDetourStart = Instant.fromEpochMilliseconds(456L),
            openDetourCategory = "Réunion",
            openDetourDescription = "point équipe",
            netTimeSettings = NetTimeSettings(enabled = true, includedCalendarIds = setOf("travail")),
        )

        DefaultDayPreferences.persist(written)

        try {
            assertEquals(written, DefaultDayPreferences.snapshots.first())
        } finally {
            // Leave the shared singleton clean for any other test relying on defaults.
            DefaultDayPreferences.persist(DayPreferencesSnapshot())
        }
    }
}
