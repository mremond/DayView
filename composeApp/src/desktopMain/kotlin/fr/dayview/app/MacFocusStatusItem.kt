package fr.dayview.app

import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit

/** Controls the native AppKit status item hosted by a tiny accessory process. */
class MacFocusStatusItem : AutoCloseable {
    private var process: Process? = null
    private var input: BufferedWriter? = null
    private var helperPath: java.nio.file.Path? = null
    private var lastTitle: String? = null

    fun update(title: String?) {
        if (!isMacOS || title == lastTitle) return
        lastTitle = title
        if (title == null) {
            stopHelper()
        } else {
            ensureHelper()
            input?.apply {
                write(title)
                newLine()
                flush()
            }
        }
    }

    override fun close() {
        lastTitle = null
        stopHelper()
        helperPath?.let(Files::deleteIfExists)
        helperPath = null
    }

    private fun ensureHelper() {
        if (process?.isAlive == true) return
        val executable = extractHelper()
        process = ProcessBuilder(executable.toString())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        input = BufferedWriter(OutputStreamWriter(process!!.outputStream))
    }

    private fun stopHelper() {
        val running = process ?: return
        runCatching {
            input?.apply {
                write(QUIT_COMMAND)
                newLine()
                flush()
                close()
            }
        }
        if (!running.waitFor(2, TimeUnit.SECONDS)) running.destroyForcibly()
        input = null
        process = null
    }

    private fun extractHelper(): java.nio.file.Path {
        helperPath?.let { if (Files.exists(it)) return it }
        val target = Files.createTempFile("dayview-focus-status-", "")
        val resource = checkNotNull(javaClass.getResourceAsStream("/macos-focus-status-helper")) {
            "macOS focus status helper is missing from application resources"
        }
        resource.use { Files.copy(it, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        Files.setPosixFilePermissions(
            target,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        helperPath = target
        return target
    }

    private companion object {
        const val QUIT_COMMAND = "__DAYVIEW_QUIT__"
        val isMacOS: Boolean = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    }
}
