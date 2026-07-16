import EventKit
import Foundation

// In-process EventKit bridge, loaded into the DayView.app JVM process via JNA.
//
// Why in-process rather than a spawned helper: on macOS the calendar (TCC) prompt is only
// presented for the *requesting process* when that process is a real, promptable app bundle.
// A spawned command-line helper is never attributed to DayView.app, so it can neither raise
// the prompt nor register the app in System Settings. Running EventKit inside the JVM makes
// DayView.app itself the requesting client — it has NSCalendarsFullAccessUsageDescription in
// its Info.plist and a live GUI event loop, so the prompt appears and the grant is keyed to
// the app bundle's stable Developer ID identity (surviving reinstalls).
//
// The C strings returned by the read functions are strdup'd; the caller must release them
// with dv_calendar_free.

// A single EKEventStore is retained for the process lifetime and reused for every call.
//
// This matters on macOS 14+: a freshly created EKEventStore reports zero calendars and zero
// events until requestFullAccessToEvents/requestAccess has been invoked on *that instance*
// and its completion has fired — even when TCC already lists the app as authorized. Creating
// a throwaway store per read (as an earlier version did) therefore returned an empty calendar
// list on every already-granted launch, because the request flow only runs the first time the
// user grants access. Reusing one store and priming its access once keeps reads populated.
private let store = EKEventStore()

private var accessPrimed = false
private let primeLock = NSLock()

private func authorizationStatusCode() -> Int32 {
    switch EKEventStore.authorizationStatus(for: .event) {
    case .authorized, .fullAccess:
        return 1
    case .notDetermined:
        return 0
    default:
        return 2
    }
}

// Wakes the shared store's connection to the calendar database before a read. When the app is
// already authorized this returns immediately with no dialog — the request simply primes the
// store. Reads only run once authorized (the caller checks dv_calendar_authorization first),
// so this never blocks on the TCC prompt; the timeout is a safeguard so a wedged request can
// never hang the JVM read thread.
private func primeAccessIfNeeded() {
    primeLock.lock()
    defer { primeLock.unlock() }
    if accessPrimed { return }
    let semaphore = DispatchSemaphore(value: 0)
    let completion: (Bool, Error?) -> Void = { _, _ in semaphore.signal() }
    if #available(macOS 14.0, *) {
        store.requestFullAccessToEvents(completion: completion)
    } else {
        store.requestAccess(to: .event, completion: completion)
    }
    _ = semaphore.wait(timeout: .now() + 5)
    accessPrimed = true
}

@_cdecl("dv_calendar_authorization")
public func dv_calendar_authorization() -> Int32 {
    authorizationStatusCode()
}

// Fire-and-forget: kicks off the system prompt, then returns. The app re-polls
// dv_calendar_authorization to observe the result.
//
// The request MUST be issued on the main thread: macOS presents the TCC dialog on the AppKit
// main run loop, and the JVM invokes this bridge from a background dispatcher. Hopping onto
// DispatchQueue.main (drained by the app's Cocoa main loop) is what makes the prompt appear.
@_cdecl("dv_calendar_request")
public func dv_calendar_request() {
    DispatchQueue.main.async {
        let handler: (Bool, Error?) -> Void = { _, _ in }
        if #available(macOS 14.0, *) {
            store.requestFullAccessToEvents(completion: handler)
        } else {
            store.requestAccess(to: .event, completion: handler)
        }
    }
}

@_cdecl("dv_calendar_calendars")
public func dv_calendar_calendars() -> UnsafeMutablePointer<CChar>? {
    primeAccessIfNeeded()
    var out = ""
    for calendar in store.calendars(for: .event) {
        out += "\(calendar.calendarIdentifier)\t\(calendar.title)\n"
    }
    return strdup(out)
}

// EventKit has no public API for an event's travel time; it is only reachable through
// the private KVC key "travelTime" (seconds). The responds(to:) guard makes an OS
// release that removes the accessor degrade to "no travel" instead of raising an
// Objective-C exception, and the finite check + clamp keep a corrupt value from
// trapping the process or swallowing the day. The same constant widens the fetch
// window in dv_calendar_busy so an event starting after the requested end, whose
// travel time overlaps the window, is still seen.
private let maxTravelSeconds: TimeInterval = 3 * 60 * 60

private func travelSeconds(_ event: EKEvent) -> TimeInterval {
    guard event.responds(to: NSSelectorFromString("travelTime")),
          let travel = (event.value(forKey: "travelTime") as? NSNumber)?.doubleValue
    else { return 0 }
    guard travel.isFinite else { return 0 }
    return min(max(travel, 0), maxTravelSeconds)
}

@_cdecl("dv_calendar_busy")
public func dv_calendar_busy(_ startMillis: Int64, _ endMillis: Int64) -> UnsafeMutablePointer<CChar>? {
    primeAccessIfNeeded()
    let start = Date(timeIntervalSince1970: Double(startMillis) / 1000.0)
    let end = Date(timeIntervalSince1970: Double(endMillis) / 1000.0)
    // Fetch up to maxTravelSeconds past the requested end so an event starting after
    // the window whose travel time overlaps it is still seen. Only extended intervals
    // overlapping [start, end] are emitted, so the function's contract is unchanged.
    let predicate = store.predicateForEvents(
        withStart: start,
        end: end.addingTimeInterval(maxTravelSeconds),
        calendars: nil
    )
    var out = ""
    for event in store.events(matching: predicate) {
        if event.isAllDay { continue }
        if event.availability != .busy { continue }
        if let me = event.attendees?.first(where: { $0.isCurrentUser }),
           me.participantStatus == .declined || me.participantStatus == .tentative {
            continue
        }
        // Travel time blocks the stretch before the event: extend the busy interval
        // upstream. Events pulled in only by the widened fetch that still don't reach
        // back into the requested window are dropped here.
        let busyStart = event.startDate.addingTimeInterval(-travelSeconds(event))
        if busyStart >= end { continue }
        let s = Int64(busyStart.timeIntervalSince1970 * 1000.0)
        let e = Int64(event.endDate.timeIntervalSince1970 * 1000.0)
        let calId = event.calendar?.calendarIdentifier ?? ""
        let title = (event.title ?? "").replacingOccurrences(of: "\t", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
        out += "\(s)\t\(e)\t\(calId)\t\(title)\n"
    }
    return strdup(out)
}

@_cdecl("dv_calendar_free")
public func dv_calendar_free(_ ptr: UnsafeMutablePointer<CChar>?) {
    free(ptr)
}
