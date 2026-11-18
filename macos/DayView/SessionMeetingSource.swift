import DayPlanKit
import DayViewKit
import Foundation

/// Adapts the Kotlin session's meeting list (epoch-millis pairs) to the day plan's `MeetingSource`.
///
/// Answers for today only: the Kotlin session caches today's meetings, whatever day is asked.
final class SessionMeetingSource: MeetingSource {
    private let session: DayViewSession
    private let calendar: Calendar

    init(session: DayViewSession, calendar: Calendar = .autoupdatingCurrent) {
        self.session = session
        self.calendar = calendar
    }

    func meetings(on day: LocalDay) -> [DateInterval] {
        let dayStart = day.start(in: calendar)
        let dayEnd = day.nextMidnight(in: calendar)
        let flat = session.dayMeetingsEpochMillis()
        var result: [DateInterval] = []
        var index: Int32 = 0
        while index + 1 < flat.size {
            let start = Date(timeIntervalSince1970: TimeInterval(flat.get(index: index)) / 1000)
            let end = Date(timeIntervalSince1970: TimeInterval(flat.get(index: index + 1)) / 1000)
            index += 2
            guard end > start, start < dayEnd, end > dayStart else { continue }
            result.append(DateInterval(start: max(start, dayStart), end: min(end, dayEnd)))
        }
        return result
    }
}
