package fr.dayview.app

import kotlinx.cinterop.ExperimentalForeignApi
import platform.EventKit.EKAuthorizationStatusFullAccess
import platform.EventKit.EKCalendar
import platform.EventKit.EKEntityType
import platform.EventKit.EKEvent
import platform.EventKit.EKEventAvailabilityBusy
import platform.EventKit.EKEventStore
import platform.EventKit.EKParticipant
import platform.EventKit.EKParticipantStatus
import platform.Foundation.NSDate
import platform.Foundation.NSNumber
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.valueForKey
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.sel_registerName
import kotlin.time.Instant

/**
 * EventKit-backed calendar reader for the native macOS app.
 *
 * ONE EKEventStore is created for the process lifetime and reused for the permission
 * request and every read — a fresh store returns empty calendars/events even when
 * authorized (the "unprimed store" bug the JVM bridge hit). The store is primed — via
 * [requestFullAccessToEventsWithCompletion] — on the first read of a launch (or by an
 * explicit [requestPermission] call); when access is already granted this shows no
 * prompt, and its completion re-invokes [onPermissionChange] on the main queue so the
 * session's existing refresh path re-reads the now-primed store moments later. Filtering
 * mirrors scripts/MacEventKitBridge.swift: busy-availability only, no all-day events, no
 * events the current user declined or marked tentative, and busy intervals extended
 * upstream by the event's travel time (private KVC key, guarded and clamped like the JVM
 * bridge).
 *
 * Main-thread only: the session scope runs on the main dispatcher, and the permission
 * completion hops back to the main queue before invoking [onPermissionChange].
 */
@OptIn(ExperimentalForeignApi::class)
class EventKitCalendarSource : CalendarSource {
    private val store = EKEventStore()

    // EventKit has no public travel-time API; see MacEventKitBridge.swift for the
    // rationale of the KVC access, the finite check, and the 3 h clamp.
    private val maxTravelSeconds = 3.0 * 60 * 60

    // Guards against re-priming on every read: a fresh EKEventStore needs the access
    // request run on it once (even when already authorized) before it returns real data.
    private var primed = false

    /** Invoked on the main queue after the user answers the access prompt. */
    var onPermissionChange: (() -> Unit)? = null

    override fun isSupported(): Boolean = true

    override fun hasPermission(): Boolean = EKEventStore.authorizationStatusForEntityType(EKEntityType.EKEntityTypeEvent) ==
        EKAuthorizationStatusFullAccess

    override fun requestPermission() {
        primed = true
        store.requestFullAccessToEventsWithCompletion { _, _ ->
            dispatch_async(dispatch_get_main_queue()) {
                onPermissionChange?.invoke()
            }
        }
    }

    // Primes an already-authorized store on the first read of a launch. No prompt
    // appears when access is already granted; the completion re-triggers the session's
    // refresh so the now-primed store is re-read moments later.
    private fun primeIfNeeded() {
        if (primed || !hasPermission()) return
        primed = true
        store.requestFullAccessToEventsWithCompletion { _, _ ->
            dispatch_async(dispatch_get_main_queue()) {
                onPermissionChange?.invoke()
            }
        }
    }

    override fun availableCalendars(): List<CalendarInfo> {
        primeIfNeeded()
        return eventCalendars().map { CalendarInfo(id = it.calendarIdentifier, displayName = it.title) }
    }

    override fun busyIntervals(
        windowStart: Instant,
        windowEnd: Instant,
        includedCalendarIds: Set<String>,
    ): List<BusyInterval> {
        primeIfNeeded()
        // Empty set = all calendars (predicate takes null); a non-empty set that matches
        // no calendars reads as no events.
        val calendars = if (includedCalendarIds.isEmpty()) {
            null
        } else {
            eventCalendars().filter { it.calendarIdentifier in includedCalendarIds }
                .ifEmpty { return emptyList() }
        }
        // Fetch up to maxTravelSeconds past the requested end so an event starting after
        // the window whose travel time overlaps it is still seen (mirrors the JVM bridge).
        val predicate = store.predicateForEventsWithStartDate(
            startDate = windowStart.toNSDate(),
            endDate = NSDate.dateWithTimeIntervalSince1970(
                windowEnd.toEpochMilliseconds() / 1000.0 + maxTravelSeconds,
            ),
            calendars = calendars,
        )
        return store.eventsMatchingPredicate(predicate)
            .orEmpty()
            .filterIsInstance<EKEvent>()
            .filterNot { it.allDay }
            .filter { it.availability == EKEventAvailabilityBusy }
            .filterNot { it.currentUserDeclinedOrTentative() }
            .mapNotNull { event ->
                val start = event.startDate ?: return@mapNotNull null
                val end = event.endDate ?: return@mapNotNull null
                // Travel time blocks the stretch before the event: extend upstream. Events
                // pulled in only by the widened fetch that still don't reach back into the
                // requested window are dropped.
                val busyStart = start.toInstant().toEpochMilliseconds() -
                    (travelSeconds(event) * 1000.0).toLong()
                if (busyStart >= windowEnd.toEpochMilliseconds()) return@mapNotNull null
                BusyInterval(
                    start = Instant.fromEpochMilliseconds(busyStart),
                    end = end.toInstant(),
                    titles = event.title?.takeUnless { it.isBlank() }?.let(::listOf) ?: emptyList(),
                    calendarId = event.calendar?.calendarIdentifier ?: "",
                )
            }
    }

    // EventKit has no public API for travel time; the private KVC key "travelTime"
    // (seconds) is the only access. respondsToSelector makes an OS release that removes
    // the accessor degrade to "no travel"; the finite check + clamp keep a corrupt value
    // from swallowing the day. Mirrors travelSeconds() in MacEventKitBridge.swift.
    private fun travelSeconds(event: EKEvent): Double {
        if (!event.respondsToSelector(sel_registerName("travelTime"))) return 0.0
        val travel = (event.valueForKey("travelTime") as? NSNumber)?.doubleValue ?: return 0.0
        if (!travel.isFinite()) return 0.0
        return travel.coerceIn(0.0, maxTravelSeconds)
    }

    private fun eventCalendars(): List<EKCalendar> = store.calendarsForEntityType(EKEntityType.EKEntityTypeEvent)
        .orEmpty()
        .filterIsInstance<EKCalendar>()

    private fun EKEvent.currentUserDeclinedOrTentative(): Boolean {
        val me = attendees.orEmpty().filterIsInstance<EKParticipant>().firstOrNull { it.currentUser }
            ?: return false
        return me.participantStatus == EKParticipantStatus.EKParticipantStatusDeclined ||
            me.participantStatus == EKParticipantStatus.EKParticipantStatusTentative
    }

    private fun Instant.toNSDate(): NSDate = NSDate.dateWithTimeIntervalSince1970(toEpochMilliseconds() / 1000.0)

    private fun NSDate.toInstant(): Instant = Instant.fromEpochMilliseconds((timeIntervalSince1970 * 1000.0).toLong())
}
