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
 * floored at .72) and grow with a large ring (capped at 1.4).
 */
internal fun countdownCounterScale(circleSize: Dp): Float = (circleSize / 380.dp).coerceIn(0.72f, 1.4f)
