package fr.dayview.app

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CountdownInteriorTest {
    private fun all(circleSize: Float, scale: Float, showSeconds: Boolean = false) = countdownInterior(
        circleSize = circleSize.dp,
        counterScale = scale,
        showSeconds = showSeconds,
        hasNet = true,
        hasBusy = true,
        hasFocus = true,
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
}
