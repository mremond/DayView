import Foundation

/// A plan the user (or the auto-accept rule) confirmed for a day, stored unfitted.
public struct AcceptedPlan: Sendable, Hashable, Codable {
    public var plan: DayPlan
    public var reviewed: Bool

    public init(plan: DayPlan, reviewed: Bool) {
        self.plan = plan
        self.reviewed = reviewed
    }
}

public struct TodayPlanState: Sendable, Hashable {
    public let day: LocalDay
    public let plan: DayPlan
    public let accepted: AcceptedPlan?
    public let fitted: [FittedBlock]

    public init(day: LocalDay, plan: DayPlan, accepted: AcceptedPlan?, fitted: [FittedBlock]) {
        self.day = day
        self.plan = plan
        self.accepted = accepted
        self.fitted = fitted
    }

    public var isAccepted: Bool { accepted != nil }

    /// Earliest start among the blocks that keep some time after meetings.
    public var firstStart: Date? {
        fitted.filter { !$0.isDropped }.compactMap(\.start).min()
    }
}

public enum TodayPlanner {
    public static func state(
        day: LocalDay, accepted: AcceptedPlan?, template: [TemplateBlock],
        meetings: [DateInterval], calendar: Calendar
    ) -> TodayPlanState {
        let plan = accepted?.plan ?? DayPlan.instantiate(template: template, day: day, calendar: calendar)
        return TodayPlanState(day: day, plan: plan, accepted: accepted, fitted: PlanFitter.fit(plan, meetings: meetings))
    }

    /// An unaccepted plan is accepted automatically once its first block starts.
    public static func autoAcceptDue(_ state: TodayPlanState, now: Date) -> Bool {
        guard !state.isAccepted, let first = state.firstStart else { return false }
        return now >= first
    }
}

public enum MorningCheckPolicy {
    public static func shouldPresentOnWindowShow(today: LocalDay, lastShown: LocalDay?, isAccepted: Bool) -> Bool {
        !isAccepted && lastShown != today
    }
}

public enum ReviewSummary {
    /// Reviewed-day and ignored-cue counts over the 7 days before `today`;
    /// nil when none of them has a stored plan or an ignored cue.
    public static func lastSevenDays(
        before today: LocalDay, history: [LocalDay: Bool], ignored: [LocalDay: Int], calendar: Calendar
    ) -> (reviewed: Int, days: Int, ignored: Int)? {
        let start = today.start(in: calendar)
        let days = (1...7).compactMap { offset -> LocalDay? in
            calendar.date(byAdding: .day, value: -offset, to: start)
                .map { LocalDay(containing: $0, calendar: calendar) }
        }
        let flags = days.compactMap { history[$0] }
        let ignoredCount = days.reduce(0) { $0 + (ignored[$1] ?? 0) }
        let hasIgnored = days.contains { ignored[$0] != nil }
        if flags.isEmpty && !hasIgnored { return nil }
        return (flags.filter { $0 }.count, 7, ignoredCount)
    }
}
