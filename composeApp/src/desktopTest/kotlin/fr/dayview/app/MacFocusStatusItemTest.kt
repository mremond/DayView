package fr.dayview.app

import kotlin.test.Test

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
}
