package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisplayScalingTest {
    @Test
    fun disabledAlwaysReturnsOne() {
        assertEquals(1f, autoDisplayScale(minDimensionDp = 1400f, enabled = false))
    }

    @Test
    fun phoneSizedScreensAreNotScaled() {
        // A typical phone's smallest dimension is at or below the reference width,
        // so it renders unchanged.
        assertEquals(1f, autoDisplayScale(minDimensionDp = 360f, enabled = true))
        assertEquals(1f, autoDisplayScale(minDimensionDp = DISPLAY_SCALE_REFERENCE_DP, enabled = true))
    }

    @Test
    fun largeCanvasesScaleUpProportionallyAndClamp() {
        // Twice the reference width -> ~2x, but never past the max clamp.
        val doubled = autoDisplayScale(minDimensionDp = DISPLAY_SCALE_REFERENCE_DP * 2f, enabled = true)
        assertEquals(DISPLAY_SCALE_MAX, doubled)

        // A very large under-reported canvas is clamped, not unbounded.
        assertEquals(DISPLAY_SCALE_MAX, autoDisplayScale(minDimensionDp = 5000f, enabled = true))

        // Between the reference and the clamp it scales linearly with the ratio.
        val mid = autoDisplayScale(minDimensionDp = DISPLAY_SCALE_REFERENCE_DP * 1.5f, enabled = true)
        assertTrue(mid in 1.49f..1.51f, "expected ~1.5, was $mid")
    }

    @Test
    fun nonPositiveSizeFallsBackToOne() {
        assertEquals(1f, autoDisplayScale(minDimensionDp = 0f, enabled = true))
        assertEquals(1f, autoDisplayScale(minDimensionDp = -10f, enabled = true))
    }
}
