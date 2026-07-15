package fr.dayview.app

import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.today_hero_ending
import fr.dayview.app.generated.resources.today_hero_ending_sources
import fr.dayview.app.generated.resources.today_hero_finished
import fr.dayview.app.generated.resources.today_hero_finished_sources
import fr.dayview.app.generated.resources.today_hero_not_started
import fr.dayview.app.generated.resources.today_hero_not_started_sources
import fr.dayview.app.generated.resources.today_hero_ongoing
import fr.dayview.app.generated.resources.today_hero_ongoing_sources
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.StringArrayResource
import org.jetbrains.compose.resources.getStringArray
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every hero pool must be non-empty and its English and French quote arrays must have
 * the same number of items, so each quote stays paired with its translation by index.
 * Each quote array also has a source array of the same size (empty string = no source),
 * so every quote has an index-aligned source slot in both locales.
 */
class HeroQuotesArrayParityTest {
    private val heroPools: List<Pair<StringArrayResource, StringArrayResource>> = listOf(
        Res.array.today_hero_not_started to Res.array.today_hero_not_started_sources,
        Res.array.today_hero_ending to Res.array.today_hero_ending_sources,
        Res.array.today_hero_finished to Res.array.today_hero_finished_sources,
        Res.array.today_hero_ongoing to Res.array.today_hero_ongoing_sources,
    )

    @Test
    fun englishAndFrenchHeroArraysHaveMatchingSizes() = runTest {
        val previous = Locale.getDefault()
        try {
            for ((quotes, sources) in heroPools) {
                Locale.setDefault(Locale.ENGLISH)
                val englishQuotes = getStringArray(quotes)
                val englishSources = getStringArray(sources)
                Locale.setDefault(Locale.FRENCH)
                val frenchQuotes = getStringArray(quotes)
                val frenchSources = getStringArray(sources)
                assertTrue(englishQuotes.isNotEmpty(), "hero array is empty")
                assertEquals(englishQuotes.size, frenchQuotes.size, "EN/FR hero array size mismatch")
                assertEquals(englishQuotes.size, englishSources.size, "EN quote/source size mismatch")
                assertEquals(frenchQuotes.size, frenchSources.size, "FR quote/source size mismatch")
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
