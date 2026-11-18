import Foundation

public struct AreaWeekLine: Sendable, Hashable {
    public let areaID: AreaID
    public let planned: TimeInterval
    public let focused: TimeInterval
    /// Deep-focus time, nil when it was never measured for this area.
    public let deep: TimeInterval?

    public init(areaID: AreaID, planned: TimeInterval, focused: TimeInterval, deep: TimeInterval? = nil) {
        self.areaID = areaID
        self.planned = planned
        self.focused = focused
        self.deep = deep
    }
}

public enum ReviewState: Sendable, Hashable {
    case reviewed, autoAccepted, noPlan
}

public struct DaySignals: Sendable, Hashable {
    public let day: LocalDay
    public let review: ReviewState
    public let ignoredCues: Int

    public init(day: LocalDay, review: ReviewState, ignoredCues: Int) {
        self.day = day
        self.review = review
        self.ignoredCues = ignoredCues
    }
}

public struct FocusTime: Sendable, Hashable {
    public let day: LocalDay
    public let areaID: AreaID
    /// The engaged Focus time.
    public let seconds: TimeInterval
    /// Deep-focus time, nil when it was not measured.
    public let deep: TimeInterval?

    public init(day: LocalDay, areaID: AreaID, seconds: TimeInterval, deep: TimeInterval? = nil) {
        self.day = day
        self.areaID = areaID
        self.seconds = seconds
        self.deep = deep
    }
}

public struct WeekSummary: Sendable, Hashable {
    public let days: [LocalDay]
    public let areas: [AreaWeekLine]
    public let signals: [DaySignals]

    public init(days: [LocalDay], areas: [AreaWeekLine], signals: [DaySignals]) {
        self.days = days
        self.areas = areas
        self.signals = signals
    }

    /// Planned versus focused time per area, and the two daily signals, from Monday through `today`.
    public static func compute(
        today: LocalDay, calendar: Calendar, accepted: [LocalDay: AcceptedPlan], focus: [FocusTime],
        ignored: [LocalDay: Int], areaOrder: [AreaID]
    ) -> WeekSummary {
        let days = weekDays(through: today, calendar: calendar)
        let inRange = Set(days)
        var planned: [AreaID: TimeInterval] = [:]
        var focused: [AreaID: TimeInterval] = [:]
        var deep: [AreaID: TimeInterval] = [:]
        var order = areaOrder
        var seen = Set(areaOrder)
        func note(_ id: AreaID) {
            if seen.insert(id).inserted { order.append(id) }
        }
        for day in days {
            for block in accepted[day]?.plan.blocks ?? [] {
                note(block.areaID)
                planned[block.areaID, default: 0] += block.end.timeIntervalSince(block.start)
            }
        }
        for entry in focus where inRange.contains(entry.day) {
            note(entry.areaID)
            focused[entry.areaID, default: 0] += entry.seconds
            if let value = entry.deep { deep[entry.areaID, default: 0] += value }
        }
        let lines = order.compactMap { id -> AreaWeekLine? in
            let line = AreaWeekLine(areaID: id, planned: planned[id] ?? 0, focused: focused[id] ?? 0, deep: deep[id])
            return line.planned == 0 && line.focused == 0 ? nil : line
        }
        let signals = days.map { day in
            DaySignals(day: day, review: reviewState(accepted[day]), ignoredCues: ignored[day] ?? 0)
        }
        return WeekSummary(days: days, areas: lines, signals: signals)
    }

    private static func reviewState(_ plan: AcceptedPlan?) -> ReviewState {
        guard let plan else { return .noPlan }
        return plan.reviewed ? .reviewed : .autoAccepted
    }

    private static func weekDays(through today: LocalDay, calendar: Calendar) -> [LocalDay] {
        let start = today.start(in: calendar)
        let back = today.weekdayISO(in: calendar) - 1
        return (0...back).reversed().compactMap { offset in
            calendar.date(byAdding: .day, value: -offset, to: start)
                .map { LocalDay(containing: $0, calendar: calendar) }
        }
    }
}
