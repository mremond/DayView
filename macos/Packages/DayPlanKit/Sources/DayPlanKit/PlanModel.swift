import Foundation

/// A recurring weekly block, in minutes from local midnight (0...1440).
public struct TemplateBlock: Sendable, Hashable, Codable {
    public var id: BlockID
    public var weekdayISO: Int
    public var startMinute: Int
    public var endMinute: Int
    public var areaID: AreaID
    public var intention: String

    public init(id: BlockID, weekdayISO: Int, startMinute: Int, endMinute: Int, areaID: AreaID, intention: String) {
        self.id = id
        self.weekdayISO = weekdayISO
        self.startMinute = startMinute
        self.endMinute = endMinute
        self.areaID = areaID
        self.intention = intention
    }
}

/// A block placed on a concrete day with absolute instants.
public struct PlannedBlock: Sendable, Hashable, Codable {
    public var id: BlockID
    public var start: Date
    public var end: Date
    public var areaID: AreaID
    public var intention: String

    public init(id: BlockID, start: Date, end: Date, areaID: AreaID, intention: String) {
        self.id = id
        self.start = start
        self.end = end
        self.areaID = areaID
        self.intention = intention
    }
}

public struct DayPlan: Sendable, Hashable, Codable {
    public let day: LocalDay
    /// Always sorted by `start`.
    public let blocks: [PlannedBlock]

    public init(day: LocalDay, blocks: [PlannedBlock]) {
        self.day = day
        self.blocks = blocks.sorted { $0.start < $1.start }
    }

    public static func instantiate(template: [TemplateBlock], day: LocalDay, calendar: Calendar) -> DayPlan {
        let weekday = day.weekdayISO(in: calendar)
        let blocks = template.filter { $0.weekdayISO == weekday }.map { t in
            PlannedBlock(
                id: BlockID(),
                start: instant(minute: t.startMinute, of: day, calendar: calendar),
                end: instant(minute: t.endMinute, of: day, calendar: calendar),
                areaID: t.areaID,
                intention: t.intention
            )
        }
        return DayPlan(day: day, blocks: blocks)
    }

    /// Extends the block's end by `by` and shifts every block starting at or after its old end.
    public func keepingGoing(blockID: BlockID, by: TimeInterval, calendar: Calendar) -> DayPlan {
        guard let target = blocks.first(where: { $0.id == blockID }) else { return self }
        let oldEnd = target.end
        let moved = blocks.map { b -> PlannedBlock in
            var b = b
            if b.id == blockID {
                b.end = b.end.addingTimeInterval(by)
            } else if b.start >= oldEnd {
                b.start = b.start.addingTimeInterval(by)
                b.end = b.end.addingTimeInterval(by)
            }
            return b
        }
        return dropping(moved, calendar: calendar)
    }

    /// Shifts the block and every later block by `by`.
    public func shiftingFrom(blockID: BlockID, by: TimeInterval, calendar: Calendar) -> DayPlan {
        guard let target = blocks.first(where: { $0.id == blockID }) else { return self }
        let moved = blocks.map { b -> PlannedBlock in
            guard b.start >= target.start else { return b }
            var b = b
            b.start = b.start.addingTimeInterval(by)
            b.end = b.end.addingTimeInterval(by)
            return b
        }
        return dropping(moved, calendar: calendar)
    }

    private func dropping(_ moved: [PlannedBlock], calendar: Calendar) -> DayPlan {
        let limit = day.nextMidnight(in: calendar)
        return DayPlan(day: day, blocks: moved.filter { $0.end <= limit })
    }

    private static func instant(minute: Int, of day: LocalDay, calendar: Calendar) -> Date {
        if minute >= 1440 { return day.nextMidnight(in: calendar) }
        return calendar.date(
            bySettingHour: minute / 60, minute: minute % 60, second: 0,
            of: day.start(in: calendar), matchingPolicy: .nextTime
        )!
    }
}
