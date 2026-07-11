package fr.dayview.app

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/** Passerelle EventKit macOS pilotée par un petit processus accessoire. */
private class MacEventKitCalendarSource : CalendarSource {
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var helperPath: java.nio.file.Path? = null

    override fun isSupported() = isMacOS

    override fun hasPermission(): Boolean = command("PERMISSION").firstOrNull() == "GRANTED"

    override fun requestPermission() {
        command("REQUEST")
    }

    override fun availableCalendars(): List<CalendarInfo> = commandUntilEnd("CALENDARS").mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size >= 2) CalendarInfo(parts[0], parts[1]) else null
    }

    override fun busyIntervals(
        windowStartMillis: Long,
        windowEndMillis: Long,
        includedCalendarIds: Set<String>,
    ): List<BusyInterval> = commandUntilEnd("BUSY $windowStartMillis $windowEndMillis").mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 3) return@mapNotNull null
        val start = parts[0].toLongOrNull() ?: return@mapNotNull null
        val end = parts[1].toLongOrNull() ?: return@mapNotNull null
        val calId = parts[2]
        if (includedCalendarIds.isNotEmpty() && calId !in includedCalendarIds) return@mapNotNull null
        val title = parts.getOrNull(3).orEmpty()
        BusyInterval(start, end, if (title.isBlank()) emptyList() else listOf(title))
    }

    private fun ensureHelper(): Boolean {
        if (!isMacOS) return false
        if (process?.isAlive == true) return true
        return runCatching {
            val executable = extractHelper()
            val started = ProcessBuilder(executable.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
            process = started
            writer = BufferedWriter(OutputStreamWriter(started.outputStream))
            reader = BufferedReader(InputStreamReader(started.inputStream))
            true
        }.getOrDefault(false)
    }

    /** Envoie une commande dont la réponse tient sur une seule ligne. */
    private fun command(cmd: String): List<String> {
        if (!ensureHelper()) return emptyList()
        return runCatching {
            writer!!.apply {
                write(cmd)
                newLine()
                flush()
            }
            listOfNotNull(reader!!.readLine())
        }.getOrDefault(emptyList())
    }

    /** Envoie une commande dont la réponse est une liste terminée par « END ». */
    private fun commandUntilEnd(cmd: String): List<String> {
        if (!ensureHelper()) return emptyList()
        return runCatching {
            writer!!.apply {
                write(cmd)
                newLine()
                flush()
            }
            val lines = mutableListOf<String>()
            while (true) {
                val line = reader!!.readLine() ?: break
                if (line == "END") break
                lines += line
            }
            lines
        }.getOrDefault(emptyList())
    }

    private fun extractHelper(): java.nio.file.Path {
        helperPath?.let { if (Files.exists(it)) return it }
        val target = Files.createTempFile("dayview-eventkit-", "")
        val resource = checkNotNull(javaClass.getResourceAsStream("/macos-eventkit-helper")) {
            "macOS EventKit helper is missing from application resources"
        }
        resource.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
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
        val isMacOS: Boolean = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    }
}

private val isMacOS: Boolean = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

actual fun createCalendarSource(): CalendarSource = if (isMacOS) MacEventKitCalendarSource() else NoopCalendarSource
