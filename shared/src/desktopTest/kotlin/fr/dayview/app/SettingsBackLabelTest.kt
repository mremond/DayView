package fr.dayview.app

import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.getString
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The settings back control keeps its up-one-level behaviour, so its label must match the
 * destination: "Today" at the settings root, "Settings" inside a sub-category. Guarding the
 * label decision here prevents it from drifting back to the old always-"Today" string.
 */
class SettingsBackLabelTest {
    @Test
    fun labelMatchesDestinationInBothLocales() = runTest {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            assertEquals("‹  TODAY", getString(settingsBackLabel(atRoot = true)))
            assertEquals("‹  SETTINGS", getString(settingsBackLabel(atRoot = false)))

            Locale.setDefault(Locale.FRENCH)
            assertEquals("‹  AUJOURD’HUI", getString(settingsBackLabel(atRoot = true)))
            assertEquals("‹  RÉGLAGES", getString(settingsBackLabel(atRoot = false)))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
