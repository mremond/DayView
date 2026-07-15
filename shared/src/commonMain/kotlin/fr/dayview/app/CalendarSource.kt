package fr.dayview.app

/** Platform factory for the calendar reader; actuals live in android/desktop. */
expect fun createCalendarSource(): CalendarSource
