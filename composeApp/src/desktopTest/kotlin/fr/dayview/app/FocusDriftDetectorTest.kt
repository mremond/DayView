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

    @Test
    fun sustainedOffGoalFiresAfterThresholdOnceGracePassed() {
        val detector = FocusDriftDetector()
        val onGoal = setOf("com.goal.app")
        // t=0 activate (on-goal), then move off-goal and stay.
        detector.observe(true, 0L, "com.goal.app", onGoal)
        // Past 30s grace; off-goal for 2 min ⇒ fires.
        assertFalse(detector.observe(true, 31_000L, "com.other.app", onGoal))
        assertTrue(detector.observe(true, 31_000L + 120_000L, "com.other.app", onGoal))
    }

    @Test
    fun returningOnGoalResetsSustainedTimer() {
        val detector = FocusDriftDetector()
        val onGoal = setOf("com.goal.app")
        detector.observe(true, 0L, "com.goal.app", onGoal)
        detector.observe(true, 31_000L, "com.other.app", onGoal) // off-goal starts
        detector.observe(true, 61_000L, "com.goal.app", onGoal) // back on-goal ⇒ reset
        assertFalse(detector.observe(true, 150_000L, "com.other.app", onGoal)) // only 89s off-goal
    }

    @Test
    fun dayViewFrontmostIsNeutralAndDoesNotResetSustainedTimer() {
        val detector = FocusDriftDetector()
        val onGoal = setOf("com.goal.app")
        detector.observe(true, 0L, "com.goal.app", onGoal)
        detector.observe(true, 31_000L, "com.other.app", onGoal) // off-goal starts at 31s
        detector.observe(true, 60_000L, "fr.dayview.app", onGoal) // neutral: timer keeps running
        assertTrue(detector.observe(true, 31_000L + 120_000L, "com.other.app", onGoal))
    }

    @Test
    fun emptyAllowlistDisablesSustainedRule() {
        val detector = FocusDriftDetector()
        detector.observe(true, 0L, "com.other.app", emptySet())
        assertFalse(detector.observe(true, 500_000L, "com.other.app", emptySet()))
    }
}

class FocusResumeDetectorTest {
    @Test
    fun existingActiveSessionTriggersRitualOnFirstObservation() {
        val detector = FocusResumeDetector()

        assertTrue(detector.observe(true, 1_000L))
        assertFalse(detector.observe(true, 2_000L))
    }

    @Test
    fun startingANewFocusNormallyDoesNotTriggerRitual() {
        val detector = FocusResumeDetector()

        assertFalse(detector.observe(false, 1_000L))
        assertFalse(detector.observe(true, 2_000L))
    }

    @Test
    fun activeSessionTriggersRitualAfterLongPollingGap() {
        val detector = FocusResumeDetector()

        detector.observe(false, 0L)
        detector.observe(true, 1_000L)

        assertFalse(detector.observe(true, 10_000L))
        assertTrue(detector.observe(true, 25_000L))
    }

    @Test
    fun expiredSessionDoesNotTriggerRitualAfterLongPollingGap() {
        val detector = FocusResumeDetector()

        detector.observe(false, 0L)
        detector.observe(true, 1_000L)

        assertFalse(detector.observe(false, 25_000L))
    }
}
