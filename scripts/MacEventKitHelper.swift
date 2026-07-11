import EventKit
import Foundation

// Passerelle EventKit pilotée ligne par ligne sur stdin, réponses terminées par « END ».
// Protocole :
//   PERMISSION           -> GRANTED | DENIED | NOTDETERMINED
//   REQUEST              -> déclenche la demande, répond GRANTED | DENIED
//   CALENDARS            -> lignes « id\tdisplayName », puis END
//   BUSY <start> <end>   -> lignes « startMillis\tendMillis\tcalendarId\ttitle » (busy, non journée entière), puis END
//   __DAYVIEW_QUIT__     -> termine le processus

private let store = EKEventStore()

private func authorizationString() -> String {
    switch EKEventStore.authorizationStatus(for: .event) {
    case .authorized, .fullAccess:
        return "GRANTED"
    case .notDetermined:
        return "NOTDETERMINED"
    default:
        return "DENIED"
    }
}

private func requestAccess() -> String {
    let semaphore = DispatchSemaphore(value: 0)
    var granted = false
    let handler: (Bool, Error?) -> Void = { ok, _ in
        granted = ok
        semaphore.signal()
    }
    if #available(macOS 14.0, *) {
        store.requestFullAccessToEvents(completion: handler)
    } else {
        store.requestAccess(to: .event, completion: handler)
    }
    semaphore.wait()
    return granted ? "GRANTED" : "DENIED"
}

private func printCalendars() {
    for calendar in store.calendars(for: .event) {
        print("\(calendar.calendarIdentifier)\t\(calendar.title)")
    }
    print("END")
}

private func printBusy(startMillis: Int64, endMillis: Int64) {
    let start = Date(timeIntervalSince1970: Double(startMillis) / 1000.0)
    let end = Date(timeIntervalSince1970: Double(endMillis) / 1000.0)
    let predicate = store.predicateForEvents(withStart: start, end: end, calendars: nil)
    for event in store.events(matching: predicate) {
        if event.isAllDay { continue }
        if event.availability != .busy { continue }
        let s = Int64(event.startDate.timeIntervalSince1970 * 1000.0)
        let e = Int64(event.endDate.timeIntervalSince1970 * 1000.0)
        let calId = event.calendar?.calendarIdentifier ?? ""
        let title = (event.title ?? "").replacingOccurrences(of: "\t", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
        print("\(s)\t\(e)\t\(calId)\t\(title)")
    }
    print("END")
}

while let line = readLine() {
    if line == "__DAYVIEW_QUIT__" {
        break
    } else if line == "PERMISSION" {
        print(authorizationString())
    } else if line == "REQUEST" {
        print(requestAccess())
    } else if line == "CALENDARS" {
        printCalendars()
    } else if line.hasPrefix("BUSY ") {
        let parts = line.split(separator: " ")
        if parts.count >= 3,
           let start = Int64(parts[1]),
           let end = Int64(parts[2]) {
            printBusy(startMillis: start, endMillis: end)
        } else {
            print("END")
        }
    }
    fflush(stdout)
}
