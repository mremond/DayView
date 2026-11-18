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
        if TodayPlanner.autoAcceptDue(next, now: current),
           plan.saveAccepted(next.plan, reviewed: false, at: current) {
            next = computeState(at: current)
        }
        if next != state { state = next }
    }

    func mainWindowShown() {
        let today = state.day
        if MorningCheckPolicy.shouldPresentOnWindowShow(
            today: today, lastShown: lastShownDay, isAccepted: state.isAccepted
        ) {
            isMorningCheckPresented = true
            defaults.set(Self.dayKey(today), forKey: Self.shownDayKey)
        }
    }

    func presentMorningCheck() {
        isMorningCheckPresented = true
    }

    /// Accepts the edited blocks as today's plan, marked reviewed, and closes the sheet.
    func accept(_ blocks: [TemplateBlock]) {
        if store(blocks, reviewed: true) { isMorningCheckPresented = false }
    }

    /// Saves edits to an already accepted plan, keeping its stored `reviewed` flag.
    func saveEdits(_ blocks: [TemplateBlock]) {
        guard let accepted = state.accepted else { return }
        if store(blocks, reviewed: accepted.reviewed) { isMorningCheckPresented = false }
    }

    var reviewSummary: (reviewed: Int, days: Int)? {
        let today = state.day
        let start = today.start(in: calendar)
        guard let from = calendar.date(byAdding: .day, value: -7, to: start),
              let through = calendar.date(byAdding: .day, value: -1, to: start) else { return nil }
        let history = plan.reviewHistory(
            from: LocalDay(containing: from, calendar: calendar),
            through: LocalDay(containing: through, calendar: calendar)
        )
        return ReviewSummary.lastSevenDays(before: today, history: history, calendar: calendar)
    }

    // MARK: Helpers

    private func store(_ blocks: [TemplateBlock], reviewed: Bool) -> Bool {
        let day = state.day
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
        defaults.string(forKey: Self.shownDayKey).flatMap(Self.parseDay)
    }

    /// "YYYY-MM-DD", zero padded.
    static func dayKey(_ day: LocalDay) -> String {
        String(format: "%04d-%02d-%02d", day.year, day.month, day.day)
    }

    static func parseDay(_ key: String) -> LocalDay? {
        let parts = key.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return LocalDay(year: parts[0], month: parts[1], day: parts[2])
    }
}
