package fr.dayview.app

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists

/** Installs a per-user LaunchAgent so the packaged macOS application opens at login. */
class MacLoginLauncher internal constructor(
    private val launchAgentsDirectory: Path = Path.of(
        System.getProperty("user.home"),
        "Library",
        "LaunchAgents",
    ),
    private val executableProvider: () -> Path? = ::currentPackagedExecutable,
    private val isMacOS: Boolean = System.getProperty("os.name").startsWith("Mac", ignoreCase = true),
) {
    private val launchAgentPath: Path
        get() = launchAgentsDirectory.resolve(LAUNCH_AGENT_FILE_NAME)

    fun isAvailable(): Boolean = isMacOS && executableProvider() != null

    fun isEnabled(): Boolean {
        val executable = executableProvider() ?: return false
        val path = launchAgentPath
        if (!path.exists()) return false
        return runCatching {
            Files.readString(path).contains("<string>${escapeXml(executable.toString())}</string>")
        }.getOrDefault(false)
    }

    fun setEnabled(enabled: Boolean): Boolean = runCatching {
        if (!enabled) {
            Files.deleteIfExists(launchAgentPath)
            return@runCatching true
        }

        check(isMacOS) { "Opening at login is only supported on macOS" }
        val executable = checkNotNull(executableProvider()) {
            "DayView must be launched from its packaged application"
        }
        Files.createDirectories(launchAgentsDirectory)
        val temporaryFile = Files.createTempFile(launchAgentsDirectory, ".dayview-login-", ".plist")
        try {
            Files.writeString(temporaryFile, launchAgentContents(executable), StandardCharsets.UTF_8)
            Files.move(
                temporaryFile,
                launchAgentPath,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            Files.deleteIfExists(temporaryFile)
        }
        true
    }.getOrDefault(false)

    internal fun launchAgentContents(executable: Path): String = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "https://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>$LAUNCH_AGENT_LABEL</string>
    <key>ProgramArguments</key>
    <array>
        <string>${escapeXml(executable.toString())}</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
</dict>
</plist>
"""

    private companion object {
        const val LAUNCH_AGENT_LABEL = "fr.dayview.app.login"
        const val LAUNCH_AGENT_FILE_NAME = "$LAUNCH_AGENT_LABEL.plist"

        fun currentPackagedExecutable(): Path? {
            val candidate = System.getProperty("jpackage.app-path")
                ?.takeIf(String::isNotBlank)
                ?: ProcessHandle.current().info().command().orElse(null)
                ?: return null
            return Path.of(candidate).toAbsolutePath().normalize()
                .takeIf { it.toString().contains(".app/Contents/MacOS/") }
        }

        fun escapeXml(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
