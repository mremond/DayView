package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeroQuotesTest {
    @Test
    fun indexIsZeroForEmptyOrSingleton() {
        assertEquals(0, heroQuoteIndex(size = 0, seed = 12345))
        assertEquals(0, heroQuoteIndex(size = 1, seed = 12345))
        assertEquals(0, heroQuoteIndex(size = 1, seed = -12345))
    }

    @Test
    fun indexStaysWithinBounds() {
        for (seed in listOf(0, 1, 7, 40, -1, -40, Int.MAX_VALUE, Int.MIN_VALUE)) {
            val index = heroQuoteIndex(size = 3, seed = seed)
            assertTrue(index in 0 until 3, "seed=$seed produced out-of-range index=$index")
        }
    }

    @Test
    fun indexIsDeterministicForSameSeedAndSize() {
        assertEquals(heroQuoteIndex(size = 5, seed = 99), heroQuoteIndex(size = 5, seed = 99))
    }

    @Test
    fun selectionSeedIsStableWithinTheSession() {
        assertEquals(
            HeroQuoteSelection.seed(HeroQuoteSlot.ONGOING),
            HeroQuoteSelection.seed(HeroQuoteSlot.ONGOING),
        )
    }
}
