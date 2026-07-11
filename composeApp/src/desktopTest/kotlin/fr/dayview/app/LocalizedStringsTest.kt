package fr.dayview.app

import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.day_available_percent
import fr.dayview.app.generated.resources.goal_progress_percent
import fr.dayview.app.generated.resources.settings_back
import fr.dayview.app.generated.resources.volume_value
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.getString
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the two behaviours the i18n switch introduced: English is the default (base)
 * language, French is a locale override, and a literal percent sign renders as a single
 * `%` (Compose substitutes %n$s but does not unescape %%).
 */
class LocalizedStringsTest {
    @Test
    fun englishIsTheDefaultAndFrenchOverridesIt() = runTest {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            assertEquals("‹  TODAY", getString(Res.string.settings_back))
            assertEquals("75% of the day available", getString(Res.string.day_available_percent, "75"))
            assertEquals("60%", getString(Res.string.goal_progress_percent, "60"))
            assertEquals("Volume: 40%", getString(Res.string.volume_value, "40"))

            Locale.setDefault(Locale.FRENCH)
            assertEquals("‹  AUJOURD’HUI", getString(Res.string.settings_back))
            assertEquals("75 % de la journée disponible", getString(Res.string.day_available_percent, "75"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
