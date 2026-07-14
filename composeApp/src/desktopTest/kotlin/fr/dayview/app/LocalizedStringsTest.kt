package fr.dayview.app

import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.day_available_percent
import fr.dayview.app.generated.resources.detour_list_title
import fr.dayview.app.generated.resources.desktop_quit_dayview
import fr.dayview.app.generated.resources.desktop_today_remaining
import fr.dayview.app.generated.resources.focus_intention_label
import fr.dayview.app.generated.resources.goal_progress_percent
import fr.dayview.app.generated.resources.goal_section_title
import fr.dayview.app.generated.resources.goal_title_label
import fr.dayview.app.generated.resources.planned_obligations_chip
import fr.dayview.app.generated.resources.planned_obligations_title
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
            assertEquals("Quit DayView", getString(Res.string.desktop_quit_dayview))
            assertEquals("Today · 4 h 12", getString(Res.string.desktop_today_remaining, "4", "12"))
            assertEquals("LONG-TERM GOAL", getString(Res.string.goal_section_title))
            assertEquals("Long-term goal", getString(Res.string.goal_title_label))
            assertEquals("Focus intention", getString(Res.string.focus_intention_label))
            assertEquals("Today’s must-dos", getString(Res.string.planned_obligations_title))
            assertEquals("Must-dos · 2 open · 1 done", getString(Res.string.planned_obligations_chip, "2", "1"))
            assertEquals("TODAY’S DETOURS", getString(Res.string.detour_list_title))

            Locale.setDefault(Locale.FRENCH)
            assertEquals("‹  AUJOURD’HUI", getString(Res.string.settings_back))
            assertEquals("75 % de la journée disponible", getString(Res.string.day_available_percent, "75"))
            assertEquals("Quitter DayView", getString(Res.string.desktop_quit_dayview))
            assertEquals("Aujourd’hui · 4 h 12", getString(Res.string.desktop_today_remaining, "4", "12"))
            assertEquals("CAP", getString(Res.string.goal_section_title))
            assertEquals("Cap à long terme", getString(Res.string.goal_title_label))
            assertEquals("Intention du Focus", getString(Res.string.focus_intention_label))
            assertEquals("Incontournables du jour", getString(Res.string.planned_obligations_title))
            assertEquals("Incontournables · À faire 2 · Faits 1", getString(Res.string.planned_obligations_chip, "2", "1"))
            assertEquals("DÉTOURS DU JOUR", getString(Res.string.detour_list_title))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
