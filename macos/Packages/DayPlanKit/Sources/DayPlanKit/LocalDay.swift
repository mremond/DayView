import Foundation

/// A calendar day without a time zone; interpreted through an injected `Calendar`.
public struct LocalDay: Sendable, Hashable, Codable {
    public let year: Int
    public let month: Int
    public let day: Int

    public init(year: Int, month: Int, day: Int) {
        self.year = year
        self.month = month
        self.day = day
    }

    public init(containing date: Date, calendar: Calendar) {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        self.init(year: c.year!, month: c.month!, day: c.day!)
    }

    public func start(in calendar: Calendar) -> Date {
        calendar.date(from: DateComponents(year: year, month: month, day: day))!
    }

    public func nextMidnight(in calendar: Calendar) -> Date {
        calendar.date(byAdding: .day, value: 1, to: start(in: calendar))!
    }

    /// ISO weekday: 1 = Monday ... 7 = Sunday.
    public func weekdayISO(in calendar: Calendar) -> Int {
        let weekday = calendar.component(.weekday, from: start(in: calendar))
        return (weekday + 5) % 7 + 1
    }
}
