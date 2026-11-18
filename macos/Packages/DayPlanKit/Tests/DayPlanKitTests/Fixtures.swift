import Foundation
@testable import DayPlanKit

let paris: Calendar = {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(identifier: "Europe/Paris")!
    return calendar
}()

extension LocalDay {
    /// 2026-11-18, a Wednesday.
    static let nov18 = LocalDay(year: 2026, month: 11, day: 18)
}

/// The instant at `hhmm` ("HH:mm") local time on `day`, in the `paris` calendar.
func at(_ hhmm: String, on day: LocalDay = .nov18) -> Date {
    let parts = hhmm.split(separator: ":").compactMap { Int($0) }
    let start = day.start(in: paris)
    return paris.date(byAdding: .minute, value: parts[0] * 60 + parts[1], to: start)!
}
