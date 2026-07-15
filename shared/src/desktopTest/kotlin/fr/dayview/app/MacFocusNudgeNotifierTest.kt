package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MacFocusNudgeNotifierTest {
    @Test
    fun buildsNotificationWithTheProvidedTitleAndBody() {
        if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) return

        val notifier = MacFocusNudgeNotifier()
        val notification = assertNotNull(notifier.buildNotification("Back to the essential", "Finish the report"))
        assertEquals("Back to the essential", notifier.readString(notification, "title"))
        assertEquals("Finish the report", notifier.readString(notification, "informativeText"))
    }

    @Test
    fun rendersTheBodyResolvedFromABlankIntentionFallback() {
        if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) return

        val notifier = MacFocusNudgeNotifier()
        val body = FocusNudgeCopy.body("   ", "One thing at a time.")
        val notification = assertNotNull(notifier.buildNotification("Back to the essential", body))
        assertEquals("One thing at a time.", notifier.readString(notification, "informativeText"))
    }

    @Test
    fun deliverDoesNotThrow() {
        if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) return

        MacFocusNudgeNotifier().notify("Back to the essential", "Finish the report")
    }
}
