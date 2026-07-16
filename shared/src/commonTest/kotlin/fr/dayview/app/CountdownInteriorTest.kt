package fr.dayview.app

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CountdownInteriorTest {
    private fun all(circleSize: Float, scale: Float, showSeconds: Boolean = false, hasEngaged: Boolean = false) = countdownInterior(
        circleSize = circleSize.dp,
        counterScale = scale,
        showSeconds = showSeconds,
        hasNet = true,
        hasBusy = true,
        hasFocus = true,
        hasEngaged = hasEngaged,
        hasDetours = true,
        hasAccolades = true,
    )

    @Test
    fun largeRingShowsEverythingAtFullSize() {
        val interior = all(circleSize = 400f, scale = 1.0f)
        assertTrue(interior.showNet)
        assertTrue(interior.showBusy)
        assertTrue(interior.showFocus)
        assertTrue(interior.showDetours)
        assertTrue(interior.showAccolades)
        assertFalse(interior.netCompact)
    }

    @Test
    fun tinyRingKeepsOnlyNumerals() {
        val interior = all(circleSize = 90f, scale = 0.45f)
        assertFalse(interior.showNet)
        assertFalse(interior.showBusy)
        assertFalse(interior.showFocus)
        assertFalse(interior.showDetours)
        assertFalse(interior.showAccolades)
    }

    @Test
    fun smallRingCullsBottomUpByPriority() {
        // A compact ring keeps the two highest-priority rows (Net, Détours)
        // and drops Focus, busy sub-line, and pips.
        val interior = all(circleSize = 160f, scale = 0.72f)
        assertTrue(interior.showNet)
        assertTrue(interior.showDetours)
        assertFalse(interior.showFocus)
        assertFalse(interior.showBusy)
        assertFalse(interior.showAccolades)
        // Below the compact threshold Net sheds its label.
        assertTrue(interior.netCompact)
    }

    @Test
    fun busySubLineRequiresNet() {
        val interior = countdownInterior(
            circleSize = 400.dp,
            counterScale = 1.0f,
            showSeconds = false,
            hasNet = false,
            hasBusy = true,
            hasFocus = false,
            hasEngaged = false,
            hasDetours = false,
            hasAccolades = false,
        )
        assertFalse(interior.showNet)
        assertFalse(interior.showBusy)
    }

    @Test
    fun netStaysFullSizeOnLargeRing() {
        assertFalse(all(circleSize = 400f, scale = 1.0f).netCompact)
    }

    @Test
    fun engagedOnlyBudgetsTheFocusRowWithoutStrictFocus() {
        // A fully-drifted session (no strict focus, only the lenient engaged figure) must
        // still budget and show the focus row from the engaged line alone.
        val interior = countdownInterior(
            circleSize = 400.dp,
            counterScale = 1.0f,
            showSeconds = false,
            hasNet = true,
            hasBusy = true,
            hasFocus = false,
            hasEngaged = true,
            hasDetours = true,
            hasAccolades = true,
        )
        assertTrue(interior.showFocus)
    }

    @Test
    fun bothFocusLinesStillFitOnALargeRing() {
        // Two-line reservation: strict + engaged together must still fit at a comfortable size.
        val interior = all(circleSize = 400f, scale = 1.0f, hasEngaged = true)
        assertTrue(interior.showFocus)
    }

    @Test
    fun midSizeDialKeepsAllRowsButDropsAccolades() {
        // The fullest interior — seconds, Net + busy, strict focus + engaged, Détours —
        // on a mid-size dial at the 0.72 scale floor: every text row must survive (the
        // rendered rows are compact enough to fit the chord) with only the pips culled.
        val interior = all(circleSize = 250f, scale = 0.72f, showSeconds = true, hasEngaged = true)
        assertTrue(interior.showNet)
        assertTrue(interior.showBusy)
        assertTrue(interior.showFocus)
        assertTrue(interior.showDetours)
        assertFalse(interior.showAccolades)
    }

    @Test
    fun tinyScaleKeepsOnlyNumeralsEvenWithRoom() {
        // Below the readability floor the secondary rows are culled regardless of the
        // geometric budget: a 0.6-scale row would render under 9.sp.
        val interior = all(circleSize = 400f, scale = 0.6f)
        assertFalse(interior.showNet)
        assertFalse(interior.showBusy)
        assertFalse(interior.showFocus)
        assertFalse(interior.showDetours)
        assertFalse(interior.showAccolades)
    }

    @Test
    fun cullIsMonotonicInPriority() {
        // In the size band where Focus (the taller row) fails to fit, the smaller busy
        // sub-line must not sneak in past it: once any present row is dropped for space,
        // every lower-priority row drops too. Regression for the take() short-circuit.
        val interior = all(circleSize = 172f, scale = 0.72f)
        assertTrue(interior.showNet)
        assertTrue(interior.showDetours)
        assertFalse(interior.showFocus)
        assertFalse(interior.showBusy)
        assertFalse(interior.showAccolades)
    }
}
