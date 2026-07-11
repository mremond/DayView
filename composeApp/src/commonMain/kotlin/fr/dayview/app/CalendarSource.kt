package fr.dayview.app

data class CalendarInfo(val id: String, val displayName: String)

data class NetTimeSettings(
    val enabled: Boolean = false,
    val includedCalendarIds: Set<String> = emptySet(),
)

interface CalendarSource {
    fun isSupported(): Boolean
    fun hasPermission(): Boolean
    fun requestPermission()
    fun availableCalendars(): List<CalendarInfo>
    fun busyIntervals(
        windowStartMillis: Long,
        windowEndMillis: Long,
        includedCalendarIds: Set<String>,
    ): List<BusyInterval>
}

object NoopCalendarSource : CalendarSource {
    override fun isSupported() = false
    override fun hasPermission() = false
    override fun requestPermission() = Unit
    override fun availableCalendars(): List<CalendarInfo> = emptyList()
    override fun busyIntervals(
        windowStartMillis: Long,
        windowEndMillis: Long,
        includedCalendarIds: Set<String>,
    ): List<BusyInterval> = emptyList()
}

expect fun createCalendarSource(): CalendarSource
