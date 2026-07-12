package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class FocusNudgeCopyTest {
    @Test
    fun bodyUsesIntentionWhenPresent() {
        assertEquals("Finish the report", FocusNudgeCopy.body("Finish the report", DEFAULT))
    }

    @Test
    fun bodyFallsBackToTheDefaultWhenBlank() {
        assertEquals(DEFAULT, FocusNudgeCopy.body("   ", DEFAULT))
    }

    private companion object {
        const val DEFAULT = "One thing at a time."
    }
}
