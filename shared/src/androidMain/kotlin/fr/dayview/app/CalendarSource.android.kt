package fr.dayview.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlin.time.Instant

internal var appContext: Context? = null

fun initCalendarSource(context: Context) {
    appContext = context.applicationContext
}

private class AndroidCalendarSource(private val context: Context) : CalendarSource {
    override fun isSupported() = true

    override fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED

    // La demande d'autorisation est déclenchée par l'UI (ActivityResult) ; ici on ne fait rien.
    override fun requestPermission() = Unit

    override fun availableCalendars(): List<CalendarInfo> {
        if (!hasPermission()) return emptyList()
        val out = mutableListOf<CalendarInfo>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                out += CalendarInfo(id = c.getLong(0).toString(), displayName = c.getString(1) ?: "")
            }
        }
        return out
    }

    override fun busyIntervals(
        windowStart: Instant,
        windowEnd: Instant,
        includedCalendarIds: Set<String>,
    ): List<BusyInterval> {
        if (!hasPermission()) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(windowStart.toEpochMilliseconds().toString())
            .appendPath(windowEnd.toEpochMilliseconds().toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.AVAILABILITY,
            CalendarContract.Instances.CALENDAR_ID,
        )
        val out = mutableListOf<BusyInterval>()
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val allDay = c.getInt(3) == 1
                val availability = c.getInt(4) // 0 = BUSY
                val calId = c.getLong(5).toString()
                if (allDay) continue
                if (availability != CalendarContract.Instances.AVAILABILITY_BUSY) continue
                if (includedCalendarIds.isNotEmpty() && calId !in includedCalendarIds) continue
                out += BusyInterval(
                    Instant.fromEpochMilliseconds(c.getLong(0)),
                    Instant.fromEpochMilliseconds(c.getLong(1)),
                    listOfNotNull(c.getString(2)),
                    calendarId = calId,
                )
            }
        }
        return out
    }

    override fun observeChanges(onChange: () -> Unit): AutoCloseable {
        // Le ContentObserver notifie sur le thread principal (Handler du Looper principal) pour
        // que la relecture déclenchée reste synchrone avec la recomposition Compose.
        val callback = onChange
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = callback()
        }
        context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        return object : AutoCloseable {
            override fun close() = context.contentResolver.unregisterContentObserver(observer)
        }
    }
}

actual fun createCalendarSource(): CalendarSource = appContext?.let { AndroidCalendarSource(it) } ?: NoopCalendarSource
