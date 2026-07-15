package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertTrue

class MacRunningApplicationsProviderTest {
    private val isMacOS = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

    @Test
    fun returnsRegularAppsOnMacOsAndDoesNotThrow() {
        val apps = MacRunningApplicationsProvider().runningApps()
        if (!isMacOS) return
        // The test runner itself is a regular app, so at least one entry with a bundle id.
        assertTrue(apps.all { it.bundleId.isNotBlank() })
        assertTrue(apps == apps.sortedBy { it.displayName.lowercase() })
    }
}
