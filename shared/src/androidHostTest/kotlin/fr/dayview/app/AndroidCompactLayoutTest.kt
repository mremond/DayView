package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidCompactLayoutTest {
    @Test
    fun compactPhoneUsesSingleColumnWithoutAutomaticZoom() {
        val widthDp = 360f
        val heightDp = 640f

        assertTrue(platformAutoScaleEnabled())
        assertEquals(1f, autoDisplayScale(minOf(widthDp, heightDp), platformAutoScaleEnabled()))
        assertFalse(useWideTodayLayout(widthDp, heightDp, fontScale = 1.3f))
    }
}
