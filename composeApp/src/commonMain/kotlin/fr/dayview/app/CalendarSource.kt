package fr.dayview.app

data class CalendarInfo(val id: String, val displayName: String)

data class NetTimeSettings(
    val enabled: Boolean = false,
    val includedCalendarIds: Set<String> = emptySet(),
)
