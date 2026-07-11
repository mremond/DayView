package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MacFocusNudgeNotifierTest {
    @Test
    fun buildsNotificationWithTitleAndProvidedBody() {
        if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) return

        val notifier = MacFocusNudgeNotifier()
        val notification = assertNotNull(notifier.buildNotification("Terminer le rapport"))
        assertEquals("Reviens à l'essentiel", notifier.readString(notification, "title"))
        assertEquals("Terminer le rapport", notifier.readString(notification, "informativeText"))
    }

    @Test
    fun fallsBackToDefaultBodyWhenIntentionBlank() {
        if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) return

        val notifier = MacFocusNudgeNotifier()
        val notification = assertNotNull(notifier.buildNotification("   "))
        assertEquals("Une seule chose à la fois.", notifier.readString(notification, "informativeText"))
    }

    @Test
    fun deliverDoesNotThrow() {
        if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) return

        MacFocusNudgeNotifier().notify("Terminer le rapport")
    }
}
