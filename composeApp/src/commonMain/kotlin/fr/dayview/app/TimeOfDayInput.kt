package fr.dayview.app

/**
 * Directional stepper snap: from a value that is not a multiple of 5, move to the
 * nearest multiple of 5 in [direction] (+1 / −1); from an aligned value, step ±5.
 * Callers clamp the result to their own range.
 */
fun snapToFive(current: Int, direction: Int): Int = when {
    direction > 0 -> (current.floorDiv(5) + 1) * 5
    current % 5 == 0 -> current - 5
    else -> current.floorDiv(5) * 5
}
