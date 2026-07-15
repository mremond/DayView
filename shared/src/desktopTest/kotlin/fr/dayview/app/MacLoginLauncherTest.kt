package fr.dayview.app

import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacLoginLauncherTest {
    private val temporaryDirectory = Files.createTempDirectory("dayview-login-test-")
    private val executable = temporaryDirectory.resolve("Day & View.app/Contents/MacOS/DayView")
    private val launcher = MacLoginLauncher(
        launchAgentsDirectory = temporaryDirectory.resolve("LaunchAgents"),
        executableProvider = { executable },
        isMacOS = true,
    )

    @AfterTest
    fun removeTemporaryFiles() {
        temporaryDirectory.toFile().deleteRecursively()
    }

    @Test
    fun enablingCreatesALaunchAgentForTheApplicationExecutable() {
        assertTrue(launcher.setEnabled(true))

        val launchAgent = temporaryDirectory.resolve("LaunchAgents/fr.dayview.app.login.plist")
        assertTrue(launchAgent.exists())
        assertTrue(Files.readString(launchAgent).contains("Day &amp; View.app/Contents/MacOS/DayView"))
        assertTrue(launcher.isEnabled())
    }

    @Test
    fun disablingRemovesTheLaunchAgent() {
        launcher.setEnabled(true)

        assertTrue(launcher.setEnabled(false))
        assertFalse(launcher.isEnabled())
        assertFalse(temporaryDirectory.resolve("LaunchAgents/fr.dayview.app.login.plist").exists())
    }

    @Test
    fun optionIsUnavailableOutsideMacOS() {
        val unsupported = MacLoginLauncher(
            launchAgentsDirectory = temporaryDirectory,
            executableProvider = { executable },
            isMacOS = false,
        )

        assertFalse(unsupported.isAvailable())
        assertFalse(unsupported.setEnabled(true))
    }
}
