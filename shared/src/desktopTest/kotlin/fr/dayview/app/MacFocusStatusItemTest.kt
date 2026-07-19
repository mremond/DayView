package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class MacFocusStatusItemTest {
    @Test
    fun nativeMinuteLabelCanBeShownUpdatedAndRemoved() {
        if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) return

        MacFocusStatusItem().use { item ->
            item.update("22m")
            item.update("21m")
            item.update(null)
        }
    }

    @Test
    fun overtimeTitleIsThePlusMinutesHeadline() {
        val end = Instant.fromEpochMilliseconds(0L)
        val progress = calculatePomodoroProgress(end + 3.minutes, 25, end)
        assertEquals("+3 min", menuBarCompactTitle(progress, breakTitle = null))
    }
}
