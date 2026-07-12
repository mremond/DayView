package fr.dayview.app

import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.today_hero_ending
import fr.dayview.app.generated.resources.today_hero_finished
import fr.dayview.app.generated.resources.today_hero_not_started
import fr.dayview.app.generated.resources.today_hero_ongoing
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.StringArrayResource
import org.jetbrains.compose.resources.getStringArray
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every hero pool must be non-empty and its English and French arrays must have the
 * same number of items, so each quote stays paired with its translation by index.
 */
class HeroQuotesArrayParityTest {
    private val heroArrays: List<StringArrayResource> = listOf(
        Res.array.today_hero_not_started,
        Res.array.today_hero_ending,
        Res.array.today_hero_finished,
        Res.array.today_hero_ongoing,
    )

    @Test
    fun englishAndFrenchHeroArraysHaveMatchingSizes() = runTest {
        val previous = Locale.getDefault()
        try {
            for (array in heroArrays) {
                Locale.setDefault(Locale.ENGLISH)
                val english = getStringArray(array)
                Locale.setDefault(Locale.FRENCH)
                val french = getStringArray(array)
                assertTrue(english.isNotEmpty(), "hero array is empty")
                assertEquals(english.size, french.size, "EN/FR hero array size mismatch")
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
