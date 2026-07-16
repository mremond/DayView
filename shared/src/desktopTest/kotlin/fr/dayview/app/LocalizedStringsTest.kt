package fr.dayview.app

import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.day_available_percent
import fr.dayview.app.generated.resources.desktop_quit_dayview
import fr.dayview.app.generated.resources.desktop_today_remaining
import fr.dayview.app.generated.resources.detour_capture_open_label
import fr.dayview.app.generated.resources.detour_list_title
import fr.dayview.app.generated.resources.focus_intention_label
import fr.dayview.app.generated.resources.goal_progress_percent
import fr.dayview.app.generated.resources.goal_section_title
import fr.dayview.app.generated.resources.goal_title_label
import fr.dayview.app.generated.resources.history_back
import fr.dayview.app.generated.resources.history_date
import fr.dayview.app.generated.resources.history_day_no_data_a11y
import fr.dayview.app.generated.resources.history_day_title
import fr.dayview.app.generated.resources.planned_obligations_count_action
import fr.dayview.app.generated.resources.planned_obligations_empty_action
import fr.dayview.app.generated.resources.planned_obligations_one_action
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
            assertEquals("Add a must-do", getString(Res.string.planned_obligations_empty_action))
            assertEquals("1 must-do", getString(Res.string.planned_obligations_one_action))
            assertEquals("2 must-dos", getString(Res.string.planned_obligations_count_action, "2"))
            assertEquals("Add a detour", getString(Res.string.detour_capture_open_label))
            assertEquals("TODAY’S DETOURS", getString(Res.string.detour_list_title))
            assertEquals("2026-07-14", getString(Res.string.history_date, "2026", "07", "14"))
            assertEquals("No history recorded for 2026-07-14.", getString(Res.string.history_day_no_data_a11y, "2026-07-14"))
            assertEquals("‹  HISTORY", getString(Res.string.history_back))
            assertEquals("TUE · 2026-07-14", getString(Res.string.history_day_title, "TUE", "2026-07-14"))

            Locale.setDefault(Locale.FRENCH)
            assertEquals("‹  AUJOURD’HUI", getString(Res.string.settings_back))
            assertEquals("75 % de la journée disponible", getString(Res.string.day_available_percent, "75"))
            assertEquals("Quitter DayView", getString(Res.string.desktop_quit_dayview))
            assertEquals("Aujourd’hui · 4 h 12", getString(Res.string.desktop_today_remaining, "4", "12"))
            assertEquals("CAP", getString(Res.string.goal_section_title))
            assertEquals("Cap à long terme", getString(Res.string.goal_title_label))
            assertEquals("Intention du Focus", getString(Res.string.focus_intention_label))
            assertEquals("Incontournables du jour", getString(Res.string.planned_obligations_title))
            assertEquals("Ajouter un incontournable", getString(Res.string.planned_obligations_empty_action))
            assertEquals("1 incontournable", getString(Res.string.planned_obligations_one_action))
            assertEquals("2 incontournables", getString(Res.string.planned_obligations_count_action, "2"))
            assertEquals("Ajouter un détour", getString(Res.string.detour_capture_open_label))
            assertEquals("DÉTOURS DU JOUR", getString(Res.string.detour_list_title))
            assertEquals("14/07/2026", getString(Res.string.history_date, "2026", "07", "14"))
            assertEquals("Aucun historique enregistré pour le 14/07/2026.", getString(Res.string.history_day_no_data_a11y, "14/07/2026"))
            assertEquals("‹  HISTORIQUE", getString(Res.string.history_back))
            assertEquals("MAR · 14/07/2026", getString(Res.string.history_day_title, "MAR", "14/07/2026"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
