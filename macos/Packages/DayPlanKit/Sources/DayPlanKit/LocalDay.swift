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

    /// "YYYY-MM-DD", zero padded.
    public var isoKey: String {
        "\(Self.padded(year, 4))-\(Self.padded(month, 2))-\(Self.padded(day, 2))"
    }

    /// Parses "YYYY-MM-DD"; nil for anything else.
    public init?(isoKey: String) {
        let parts = isoKey.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0].count == 4, parts[1].count == 2, parts[2].count == 2,
              let year = Int(parts[0]), let month = Int(parts[1]), let day = Int(parts[2]),
              (1...12).contains(month), (1...31).contains(day) else { return nil }
        self.init(year: year, month: month, day: day)
    }

    private static func padded(_ value: Int, _ width: Int) -> String {
        let digits = String(value)
        return String(repeating: "0", count: max(0, width - digits.count)) + digits
    }
}
