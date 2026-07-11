package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class FocusNudgeCopyTest {
    @Test
    fun bodyUsesIntentionWhenPresent() {
        assertEquals("Terminer le rapport", FocusNudgeCopy.body("Terminer le rapport"))
    }

    @Test
    fun bodyFallsBackWhenBlank() {
        assertEquals("Une seule chose à la fois.", FocusNudgeCopy.body("   "))
    }

    @Test
    fun titleIsStable() {
        assertEquals("Reviens à l'essentiel", FocusNudgeCopy.TITLE)
    }
}
