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

// Retained for the lifetime of an in-flight permission request so ARC does not release the
// store before the asynchronous completion fires.
private let requestStore = EKEventStore()

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
            requestStore.requestFullAccessToEvents(completion: handler)
        } else {
            requestStore.requestAccess(to: .event, completion: handler)
        }
    }
}

@_cdecl("dv_calendar_calendars")
public func dv_calendar_calendars() -> UnsafeMutablePointer<CChar>? {
    let store = EKEventStore()
    var out = ""
    for calendar in store.calendars(for: .event) {
        out += "\(calendar.calendarIdentifier)\t\(calendar.title)\n"
    }
    return strdup(out)
}

@_cdecl("dv_calendar_busy")
public func dv_calendar_busy(_ startMillis: Int64, _ endMillis: Int64) -> UnsafeMutablePointer<CChar>? {
    let store = EKEventStore()
    let start = Date(timeIntervalSince1970: Double(startMillis) / 1000.0)
    let end = Date(timeIntervalSince1970: Double(endMillis) / 1000.0)
    let predicate = store.predicateForEvents(withStart: start, end: end, calendars: nil)
    var out = ""
    for event in store.events(matching: predicate) {
        if event.isAllDay { continue }
        if event.availability != .busy { continue }
        let s = Int64(event.startDate.timeIntervalSince1970 * 1000.0)
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
