import Foundation
import Combine
import DayPlanKit

/// Runs the Focus: owns the `FocusEngine`, persists it, offers Keep going and queues closures.
@MainActor
final class FocusModel: ObservableObject {
    @Published private(set) var current: FocusSession?
    @Published private(set) var pendingClosures: [FocusRecord] = []
    @Published private(set) var breakStartedAt: Date?
    @Published private(set) var keepGoing: KeepGoingOffer?
    /// The events of the last tick that produced any, for cues.
    @Published private(set) var lastEvents: [FocusEvent] = []

    /// Cue pipeline hook (Task 5): due cues, the tick's events and the plan state.
    /// Cues from ticks that ran before this was set are buffered and delivered when it is assigned,
    /// so the launch-time start cue is not lost.
    var onCues: (([ScheduledCue], [FocusEvent], TodayPlanState) -> Void)? {
        didSet { flushBufferedCues() }
    }
    private var bufferedCues: (due: [ScheduledCue], events: [FocusEvent], state: TodayPlanState)?

    static let engineKey = "focus.engineState"
    static let lastAreaKey = "focus.lastAreaID"
    static let lastMinutesKey = "focus.lastMinutes"

    private let todayPlan: TodayPlanModel
    private let plan: PlanModel
    private let meetings: MeetingSource
    private let calendar: Calendar
    private let defaults: UserDefaults
    private let now: () -> Date
    private var engine: FocusEngine
    private var savedEngine: FocusEngine?
    private var timer: Timer?
    private var delivery = CueDelivery()
    private var cachedSchedule: (fitted: [FittedBlock], meetings: [DateInterval], cues: [ScheduledCue])?

    init(
        todayPlan: TodayPlanModel, plan: PlanModel, meetings: MeetingSource,
        calendar: Calendar = .autoupdatingCurrent, defaults: UserDefaults = .standard,
        now: @escaping () -> Date = Date.init
    ) {
        self.todayPlan = todayPlan
        self.plan = plan
        self.meetings = meetings
        self.calendar = calendar
        self.defaults = defaults
        self.now = now
        if let data = defaults.data(forKey: Self.engineKey) {
            do {
                engine = try JSONDecoder().decode(FocusEngine.self, from: data)
                savedEngine = engine.withoutClock()
            } catch {
                NSLog("DayView: focus engine state unreadable, starting fresh: \(error)")
                engine = FocusEngine()
            }
        } else {
            engine = FocusEngine()
        }
        publish()
        tick()
        let timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    deinit { timer?.invalidate() }

    // MARK: Read accessors

    var activeArea: Area? {
        current.flatMap { session in plan.areas.first { $0.id == session.areaID } }
    }

    var lastAreaID: AreaID? {
        defaults.string(forKey: Self.lastAreaKey).flatMap(UUID.init(uuidString:)).map { AreaID($0) }
    }

    var lastMinutes: Int {
        let stored = defaults.integer(forKey: Self.lastMinutesKey)
        return FocusEngine.manualMinutes.contains(stored) ? stored : 25
    }

    // MARK: Actions

    func tick() {
        todayPlan.tick()
        let state = todayPlan.state
        let current = now()
        var events = engine.beginDay(state.day, calendar: calendar)
        events += engine.advance(to: current, plan: state.fitted)
        let dayMeetings = meetings.meetings(on: state.day)
        let offer = engine.keepGoingOffer(now: current, plan: state.fitted, meetings: dayMeetings)
        publish(offer: offer, events: events)
        persist()
        let due = delivery.due(schedule(state.fitted, dayMeetings), fitted: state.fitted, now: current)
        if !due.isEmpty || !events.isEmpty { deliver(due, events, state) }
    }

    func startManual(areaID: AreaID, intention: String, minutes: Int) {
        let minutes = min(max(minutes, FocusEngine.manualMinutes.lowerBound), FocusEngine.manualMinutes.upperBound)
        defaults.set(areaID.raw.uuidString, forKey: Self.lastAreaKey)
        defaults.set(minutes, forKey: Self.lastMinutesKey)
        apply(engine.startManual(areaID: areaID, intention: intention, minutes: minutes, now: now()))
    }

    /// Five minutes in the last used area (or the first area).
    func quickStart(intention: String) {
        guard let area = lastAreaID ?? plan.areas.first?.id else { return }
        let areaID = plan.areas.contains { $0.id == area } ? area : (plan.areas.first?.id ?? area)
        defaults.set(areaID.raw.uuidString, forKey: Self.lastAreaKey)
        apply(engine.startManual(areaID: areaID, intention: intention, minutes: 5, now: now()))
    }

    func stop() {
        apply(engine.stop(now: now(), plan: todayPlan.state.fitted))
    }

    func keepGoingNow() {
        let state = todayPlan.state
        let dayMeetings = meetings.meetings(on: state.day)
        guard let offer = engine.keepGoingOffer(now: now(), plan: state.fitted, meetings: dayMeetings) else { return }
        let result = KeepGoing.apply(offer, to: state.plan, meetings: dayMeetings, calendar: calendar)
        guard todayPlan.replaceAccepted(result.plan) else { return }
        var events: [FocusEvent] = []
        if let until = result.reopenUntil { events = engine.reopenInterrupted(until: until) }
        todayPlan.tick()
        apply(events)
    }

    func close(_ recordID: UUID, outcome: ClosureOutcome, intention: String) {
        guard let record = engine.pendingClosures.first(where: { $0.session.id == recordID }),
              plan.saveClosedFocus(record, outcome: outcome, intention: intention, area: area(for: record), calendar: calendar)
        else { return }
        _ = engine.close(recordID: recordID)
        publish()
        persist()
    }

    // MARK: Helpers

    private func area(for record: FocusRecord) -> Area {
        let id = record.session.areaID
        return plan.areas.first { $0.id == id }
            ?? Area(id: id, name: L("Deleted area"), colorIndex: 0, order: 0)
    }

    private func apply(_ events: [FocusEvent]) {
        let state = todayPlan.state
        let offer = engine.keepGoingOffer(
            now: now(), plan: state.fitted, meetings: meetings.meetings(on: state.day)
        )
        publish(offer: offer, events: events)
        persist()
        if !events.isEmpty { deliver([], events, state) }
    }

    private func publish(offer: KeepGoingOffer? = nil, events: [FocusEvent] = []) {
        if current != engine.current { current = engine.current }
        if pendingClosures != engine.pendingClosures { pendingClosures = engine.pendingClosures }
        if breakStartedAt != engine.breakStartedAt { breakStartedAt = engine.breakStartedAt }
        if keepGoing != offer { keepGoing = offer }
        if !events.isEmpty { lastEvents = events }
    }

    private func deliver(_ due: [ScheduledCue], _ events: [FocusEvent], _ state: TodayPlanState) {
        if let onCues {
            onCues(due, events, state)
        } else {
            let old = bufferedCues
            bufferedCues = ((old?.due ?? []) + due, (old?.events ?? []) + events, state)
        }
    }

    private func flushBufferedCues() {
        guard let onCues, let buffered = bufferedCues else { return }
        bufferedCues = nil
        onCues(buffered.due, buffered.events, buffered.state)
    }

    /// Writes the engine only when its state changed; the advance clock alone does not count.
    private func persist() {
        let comparable = engine.withoutClock()
        guard comparable != savedEngine, let data = try? JSONEncoder().encode(engine) else { return }
        defaults.set(data, forKey: Self.engineKey)
        savedEngine = comparable
    }

    private func schedule(_ fitted: [FittedBlock], _ dayMeetings: [DateInterval]) -> [ScheduledCue] {
        if let cached = cachedSchedule, cached.fitted == fitted, cached.meetings == dayMeetings { return cached.cues }
        let cues = CueScheduler.schedule(fitted, meetings: dayMeetings)
        cachedSchedule = (fitted, dayMeetings, cues)
        return cues
    }
}
