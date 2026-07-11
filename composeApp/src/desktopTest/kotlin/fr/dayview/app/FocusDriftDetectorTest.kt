package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FocusDriftDetectorTest {
    @Test
    fun fourQuickApplicationChangesTriggerAReminderAfterGracePeriod() {
        val detector = FocusDriftDetector()

        assertFalse(detector.observe(true, 0L, "app.one"))
        assertFalse(detector.observe(true, 31_000L, "app.two"))
        assertFalse(detector.observe(true, 36_000L, "app.three"))
        assertFalse(detector.observe(true, 41_000L, "app.four"))
        assertTrue(detector.observe(true, 46_000L, "app.five"))
    }

    @Test
    fun slowChangesOutsideObservationWindowDoNotTriggerAReminder() {
        val detector = FocusDriftDetector()

        assertFalse(detector.observe(true, 0L, "app.one"))
        assertFalse(detector.observe(true, 31_000L, "app.two"))
        assertFalse(detector.observe(true, 80_000L, "app.three"))
        assertFalse(detector.observe(true, 129_000L, "app.four"))
        assertFalse(detector.observe(true, 178_000L, "app.five"))
    }

    @Test
    fun dayViewActivationIsIgnored() {
        val detector = FocusDriftDetector()

        assertFalse(detector.observe(true, 0L, "app.one"))
        assertFalse(detector.observe(true, 31_000L, "fr.dayview.app"))
        assertFalse(detector.observe(true, 32_000L, "app.two"))
        assertFalse(detector.observe(true, 33_000L, "fr.dayview.app"))
        assertFalse(detector.observe(true, 34_000L, "app.three"))
    }

    @Test
    fun stoppingFocusResetsTheDetector() {
        val detector = FocusDriftDetector()

        detector.observe(true, 0L, "app.one")
        detector.observe(true, 31_000L, "app.two")
        detector.observe(true, 32_000L, "app.three")
        detector.observe(false, 33_000L, "app.three")

        assertFalse(detector.observe(true, 34_000L, "app.four"))
        assertFalse(detector.observe(true, 65_000L, "app.five"))
    }
}
