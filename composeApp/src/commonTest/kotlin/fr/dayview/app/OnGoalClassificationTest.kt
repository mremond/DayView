package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class OnGoalClassificationTest {
    private val onGoal = setOf("com.processone.draftline")

    @Test fun inSetIsOnGoal() = assertEquals(OnGoalState.ON_GOAL, classifyFrontmost("com.processone.draftline", onGoal))

    @Test fun notInSetIsOffGoal() = assertEquals(OnGoalState.OFF_GOAL, classifyFrontmost("com.apple.Safari", onGoal))

    @Test fun dayViewItselfIsNeutral() = assertEquals(OnGoalState.NEUTRAL, classifyFrontmost("fr.dayview.app", onGoal))

    @Test fun blankOrNullIsNeutral() {
        assertEquals(OnGoalState.NEUTRAL, classifyFrontmost(null, onGoal))
        assertEquals(OnGoalState.NEUTRAL, classifyFrontmost("  ", onGoal))
    }
}
