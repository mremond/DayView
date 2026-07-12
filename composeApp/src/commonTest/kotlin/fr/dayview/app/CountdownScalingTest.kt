package fr.dayview.app

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CountdownScalingTest {
    @Test
    fun circleSizeClampsToCeiling() {
        assertEquals(720.dp, countdownCircleSize(available = 1200.dp))
        assertEquals(300.dp, countdownCircleSize(available = 300.dp))
    }

    @Test
    fun counterScaleTracksRingWithinBounds() {
        // Small ring floors at .72; matches today's compact/mini behaviour.
        assertEquals(0.72f, countdownCounterScale(200.dp))
        // At the reference 380.dp the scale is exactly 1.0.
        assertEquals(1.0f, countdownCounterScale(380.dp))
        // A large ring lifts the numerals above 1.0, capped at 1.4.
        assertEquals(1.4f, countdownCounterScale(720.dp))
        // Between the reference and the cap it scales linearly.
        assertTrue(countdownCounterScale(456.dp) in 1.19f..1.21f)
    }
}
