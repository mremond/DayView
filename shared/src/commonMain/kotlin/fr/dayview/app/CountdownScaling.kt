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

/**
 * Which secondary rows survive inside the ring and whether Net renders in its compact,
 * label-less form. Decided purely from the ring diameter and counter scale so the interior
 * degrades the same way across phone, mini/compact desktop, and Supernote — no per-device
 * breakpoints. Rows are culled bottom-up by priority (pips → busy → Focus → Détours), the
 * countdown numerals themselves are never part of this budget.
 *
 * The height constants are empirical (they approximate each scaled row's rendered height in
 * dp); tune them by eye with `./gradlew :composeApp:run`, not by changing the algorithm.
 */
internal data class CountdownInterior(
    val showNet: Boolean,
    val netCompact: Boolean,
    val showBusy: Boolean,
    val showFocus: Boolean,
    val showDetours: Boolean,
    val showAccolades: Boolean,
)

// Fraction of the ring diameter usable as vertical room for the centered content column: a
// centered text column ~0.7·d wide leaves ~0.64·d of vertical chord inside the circle.
private const val INTERIOR_CONTENT_HEIGHT_FRACTION = 0.64f

// At or below this counter scale the "Net" label is dropped so the value stays on one line.
private const val NET_COMPACT_SCALE_THRESHOLD = 0.8f

// Below this counter scale (dials under ~144.dp, where the 0.72 floor relaxes) secondary
// rows would render under ~9.sp — unreadable — so the interior keeps only the numerals.
private const val MIN_SECONDARY_ROWS_SCALE = 0.7f

// Unscaled row-height estimates (dp). Numerals block reserved first, then secondary rows.
// Each row is its 6.dp spacer plus the explicit lineHeight its Text pins in
// DayViewTodayScreen (both scale with counterScale); keep the two in step.
private const val RESERVE_HEADER = 15f
private const val RESERVE_HEADER_SPACER = 8f
private const val RESERVE_NUMERALS = 61f
private const val RESERVE_SECONDS = 16f
private const val ROW_NET = 25f
private const val ROW_DETOURS = 24f
private const val ROW_FOCUS = 24f
private const val ROW_BUSY = 15f
private const val ROW_ACCOLADES = 34f

internal fun countdownInterior(
    circleSize: Dp,
    counterScale: Float,
    showSeconds: Boolean,
    hasNet: Boolean,
    hasBusy: Boolean,
    hasFocus: Boolean,
    hasEngaged: Boolean,
    hasDetours: Boolean,
    hasAccolades: Boolean,
): CountdownInterior {
    val interiorHeight = circleSize.value * INTERIOR_CONTENT_HEIGHT_FRACTION
    val reserve =
        (RESERVE_HEADER + RESERVE_HEADER_SPACER + RESERVE_NUMERALS + if (showSeconds) RESERVE_SECONDS else 0f) *
            counterScale
    var remaining = interiorHeight - reserve
    // Bottom-up cull: once a present row fails to fit, the budget is spent — every
    // lower-priority row is dropped too, even one that is individually smaller. This keeps
    // the cull monotonic in priority (a higher-priority row never disappears while a
    // lower-priority detail survives). An absent row is skipped without spending the budget.
    // Below the readability floor the budget starts exhausted: numerals only.
    var budgetExhausted = counterScale < MIN_SECONDARY_ROWS_SCALE

    fun take(present: Boolean, base: Float): Boolean {
        if (!present) return false
        if (budgetExhausted) return false
        val height = base * counterScale
        if (height > remaining) {
            budgetExhausted = true
            return false
        }
        remaining -= height
        return true
    }

    // Priority high → low. The busy sub-line only survives where Net does.
    val showNet = take(hasNet, ROW_NET)
    val showDetours = take(hasDetours, ROW_DETOURS)
    val focusLines = (if (hasFocus) 1 else 0) + (if (hasEngaged) 1 else 0)
    val showFocus = take(focusLines > 0, ROW_FOCUS * focusLines)
    val showBusy = take(showNet && hasBusy, ROW_BUSY)
    val showAccolades = take(hasAccolades, ROW_ACCOLADES)

    return CountdownInterior(
        showNet = showNet,
        netCompact = showNet && counterScale <= NET_COMPACT_SCALE_THRESHOLD,
        showBusy = showBusy,
        showFocus = showFocus,
        showDetours = showDetours,
        showAccolades = showAccolades,
    )
}
