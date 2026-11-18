import Foundation
import Combine
import DayPlanKit

/// Today's plan: the proposal (or accepted plan) fitted around meetings, the
/// auto-accept rule, and the morning check presentation state.
@MainActor
final class TodayPlanModel: ObservableObject {
    @Published private(set) var state: TodayPlanState
    @Published var isMorningCheckPresented = false

    static let shownDayKey = "dayplan.morningCheckShownDay"

    private let plan: PlanModel
    private let meetings: MeetingSource
    private let calendar: Calendar
    private let defaults: UserDefaults
    private let now: () -> Date
    private var timer: Timer?
    private var lastFailedAutoAccept: Date?
    private static let autoAcceptRetryInterval: TimeInterval = 60
    private var cancellables: Set<AnyCancellable> = []

    init(
        plan: PlanModel, meetings: MeetingSource,
        calendar: Calendar = .autoupdatingCurrent, defaults: UserDefaults = .standard,
        now: @escaping () -> Date = Date.init
    ) {
        self.plan = plan
        self.meetings = meetings
        self.calendar = calendar
        self.defaults = defaults
        self.now = now
        self.state = TodayPlanner.state(
            day: LocalDay(containing: now(), calendar: calendar),
            accepted: nil, template: [], meetings: [], calendar: calendar
        )
        tick()
        let timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
        // @Published publishes in willSet, so tick on the next runloop turn to read the new value.
        plan.$template.dropFirst().receive(on: RunLoop.main).sink { [weak self] _ in self?.tick() }
            .store(in: &cancellables)
        plan.$areas.dropFirst().receive(on: RunLoop.main).sink { [weak self] _ in self?.tick() }
            .store(in: &cancellables)
    }

    deinit { timer?.invalidate() }

    func tick() {
        let current = now()
        var next = computeState(at: current)
        if TodayPlanner.autoAcceptDue(next, now: current), autoAcceptAllowed(at: current) {
            if plan.saveAccepted(next.plan, reviewed: false, at: current) {
                lastFailedAutoAccept = nil
                next = computeState(at: current)
            } else {
                lastFailedAutoAccept = current
            }
        }
        if next != state { state = next }
    }

    /// After a failed save, wait before retrying so a broken store is not hit every tick.
    private func autoAcceptAllowed(at date: Date) -> Bool {
        guard let failed = lastFailedAutoAccept else { return true }
        return date.timeIntervalSince(failed) >= Self.autoAcceptRetryInterval
    }

    func mainWindowShown() {
        let today = state.day
        if MorningCheckPolicy.shouldPresentOnWindowShow(
            today: today, lastShown: lastShownDay, isAccepted: state.isAccepted
        ) {
            isMorningCheckPresented = true
            defaults.set(today.isoKey, forKey: Self.shownDayKey)
        }
    }

    func presentMorningCheck() {
        isMorningCheckPresented = true
    }

    /// Accepts the blocks built for `day` as that day's plan, marked reviewed, and closes the sheet.
    /// Refused when `day` is no longer today.
    func accept(_ blocks: [TemplateBlock], for day: LocalDay) {
        if store(blocks, for: day, reviewed: true) { isMorningCheckPresented = false }
    }

    /// Saves edits made for `day` to an already accepted plan, keeping its stored `reviewed` flag.
    /// Refused when `day` is no longer today.
    func saveEdits(_ blocks: [TemplateBlock], for day: LocalDay) {
        guard let accepted = state.accepted else { return }
        if store(blocks, for: day, reviewed: accepted.reviewed) { isMorningCheckPresented = false }
    }

    /// Replaces today's accepted plan with `newPlan` (Keep going), keeping the stored `reviewed` flag.
    /// Refused when `newPlan` is not for the current day.
    @discardableResult
    func replaceAccepted(_ newPlan: DayPlan) -> Bool {
        guard newPlan.day == state.day else {
            NSLog("DayView: refused to replace the plan of %@ on %@", newPlan.day.isoKey, state.day.isoKey)
            return false
        }
        let saved = plan.saveAccepted(newPlan, reviewed: state.accepted?.reviewed ?? false, at: now())
        tick()
        return saved
    }

    var reviewSummary: (reviewed: Int, days: Int, ignored: Int)? {
        let today = state.day
        let start = today.start(in: calendar)
        guard let from = calendar.date(byAdding: .day, value: -7, to: start),
              let through = calendar.date(byAdding: .day, value: -1, to: start) else { return nil }
        let history = plan.reviewHistory(
            from: LocalDay(containing: from, calendar: calendar),
            through: LocalDay(containing: through, calendar: calendar)
        )
        let ignored = plan.ignoredCues(
            from: LocalDay(containing: from, calendar: calendar),
            through: LocalDay(containing: through, calendar: calendar)
        )
        guard let summary = ReviewSummary.lastSevenDays(
            before: today, history: history, ignored: ignored, calendar: calendar
        ) else { return nil }
        return (summary.reviewed, summary.days, summary.ignored)
    }

    // MARK: Helpers

    private func store(_ blocks: [TemplateBlock], for day: LocalDay, reviewed: Bool) -> Bool {
        guard day == state.day else {
            NSLog("DayView: refused to save a plan built for %@ on %@", day.isoKey, state.day.isoKey)
            return false
        }
        let planned = DayPlan.fromMinuteBlocks(blocks, day: day, calendar: calendar)
        let saved = plan.saveAccepted(planned, reviewed: reviewed, at: now())
        tick()
        return saved
    }

    private func computeState(at date: Date) -> TodayPlanState {
        let day = LocalDay(containing: date, calendar: calendar)
        return TodayPlanner.state(
            day: day, accepted: plan.acceptedPlan(for: day), template: plan.template,
            meetings: meetings.meetings(on: day), calendar: calendar
        )
    }

    private var lastShownDay: LocalDay? {
        defaults.string(forKey: Self.shownDayKey).flatMap { LocalDay(isoKey: $0) }
    }
}
