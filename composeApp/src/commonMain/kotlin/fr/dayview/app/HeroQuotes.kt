package fr.dayview.app

import kotlin.random.Random

/** The four day-states the hero message reacts to. */
enum class HeroQuoteSlot { NOT_STARTED, ENDING, FINISHED, ONGOING }

/**
 * Maps a per-launch [seed] to a stable index into a quote pool of [size] items.
 * Returns 0 for an empty or single-item pool; otherwise a non-negative index in
 * `0 until size`, so it is safe for any Int seed (including negatives).
 */
fun heroQuoteIndex(size: Int, seed: Int): Int = if (size <= 1) 0 else ((seed % size) + size) % size

/**
 * Holds one random seed per slot, generated once for the process lifetime, so the
 * chosen quote stays fixed for the whole session and re-rolls on the next launch.
 */
object HeroQuoteSelection {
    private val seeds: Map<HeroQuoteSlot, Int> =
        HeroQuoteSlot.entries.associateWith { Random.nextInt() }

    fun seed(slot: HeroQuoteSlot): Int = seeds.getValue(slot)
}
