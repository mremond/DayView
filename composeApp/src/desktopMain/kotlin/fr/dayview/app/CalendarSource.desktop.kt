package fr.dayview.app

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlin.time.Instant

/**
 * macOS EventKit gateway. The bridge dylib is loaded into this (DayView.app) process, so the
 * calendar permission request and reads are attributed to the app bundle itself — see the
 * rationale in scripts/MacEventKitBridge.swift.
 */
private class MacEventKitCalendarSource : CalendarSource {
    override fun isSupported() = isMacOS

    override fun hasPermission(): Boolean = bridge?.dv_calendar_authorization() == AUTH_GRANTED

    override fun requestPermission() {
        bridge?.dv_calendar_request()
    }

    override fun availableCalendars(): List<CalendarInfo> = query { it.dv_calendar_calendars() }.mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size >= 2) CalendarInfo(parts[0], parts[1]) else null
    }

    override fun busyIntervals(
        windowStart: Instant,
        windowEnd: Instant,
        includedCalendarIds: Set<String>,
    ): List<BusyInterval> = query {
        it.dv_calendar_busy(windowStart.toEpochMilliseconds(), windowEnd.toEpochMilliseconds())
    }.mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 3) return@mapNotNull null
        val start = parts[0].toLongOrNull() ?: return@mapNotNull null
        val end = parts[1].toLongOrNull() ?: return@mapNotNull null
        val calId = parts[2]
        if (includedCalendarIds.isNotEmpty() && calId !in includedCalendarIds) return@mapNotNull null
        val title = parts.getOrNull(3).orEmpty()
        BusyInterval(
            Instant.fromEpochMilliseconds(start),
            Instant.fromEpochMilliseconds(end),
            if (title.isBlank()) emptyList() else listOf(title),
            calendarId = calId,
        )
    }

    /** Runs a read that returns a newline-delimited C string, releasing the native buffer after use. */
    private fun query(call: (EventKitBridge) -> Pointer?): List<String> {
        val lib = bridge ?: return emptyList()
        val ptr = call(lib) ?: return emptyList()
        val text = try {
            ptr.getString(0, Charsets.UTF_8.name())
        } finally {
            lib.dv_calendar_free(ptr)
        }
        return text.split('\n').filter { it.isNotEmpty() }
    }

    // JNA maps methods to the dylib's exported C symbols by name.
    @Suppress("ktlint:standard:function-naming")
    private interface EventKitBridge : Library {
        fun dv_calendar_authorization(): Int

        fun dv_calendar_request()

        fun dv_calendar_calendars(): Pointer?

        fun dv_calendar_busy(startMillis: Long, endMillis: Long): Pointer?

        fun dv_calendar_free(ptr: Pointer?)
    }

    private companion object {
        const val AUTH_GRANTED = 1

        val bridge: EventKitBridge? by lazy {
            if (!isMacOS) return@lazy null
            runCatching {
                val dylib = MacHelpers.extract("/libdayview_eventkit.dylib")
                Native.load(dylib.toString(), EventKitBridge::class.java)
            }.getOrNull()
        }
    }
}

private val isMacOS: Boolean = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

actual fun createCalendarSource(): CalendarSource = if (isMacOS) MacEventKitCalendarSource() else NoopCalendarSource
