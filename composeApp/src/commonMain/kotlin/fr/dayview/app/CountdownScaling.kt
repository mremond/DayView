package fr.dayview.app

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Diameter for the countdown ring given the available square, clamped to a sane
 * maximum so it fills large screens (Supernote, maximized desktop) without becoming
 * absurd on very large monitors.
 */
internal fun countdownCircleSize(available: Dp, max: Dp = 720.dp): Dp = minOf(available, max)

/**
 * Type scale for the counter numerals. Tracks the ring around a 380.dp reference so the
 * numerals keep their proportion: they never dwarf a small dial (mini/compact windows,
 * floored at .72) and grow with a large ring (capped at 1.4). Below ~144.dp the fixed
 * floor itself would overflow the dial (the "HH h MM" row is wider than the ring, so the
 * minutes wrap and push the header out of the circle); there the floor relaxes and the
 * numerals track circleSize/200 — as large as still fits.
 */
internal fun countdownCounterScale(circleSize: Dp): Float {
    val floor = minOf(0.72f, circleSize / 200.dp)
    return (circleSize / 380.dp).coerceIn(floor, 1.4f)
}
