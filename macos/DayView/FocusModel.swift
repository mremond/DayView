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
    /// Idle panel preferences (last used area / duration), shared by the panel and the Focus menu.
    @Published private(set) var lastAreaID: AreaID?
    @Published private(set) var lastMinutes: Int = 25
    /// Idle intention draft, shared by the Start focus button and the menu command; not persisted.
    @Published var draftIntention: String = ""

    /// Cue pipeline hook (Task 5): due cues, the tick's events and the plan state.
    /// Cues from ticks that ran before this was set are buffered and delivered when it is assigned,
    /// so the launch-time start cue is not lost.
    var onCues: (([ScheduledCue], [FocusEvent], TodayPlanState) -> Void)? {
        didSet { flushBufferedCues() }
    }
    /// Called once when a manual Focus has run twice its planned duration (the closing suggestion).
    var onOvertimeReminder: ((FocusSession) -> Void)?
    /// Called for each record a Focus end produces (ended or taken over), after the record is queued.
    var onFocusEnded: ((FocusRecord) -> Void)?
    private var bufferedCues: (due: [ScheduledCue], events: [FocusEvent], state: TodayPlanState)?

    static let engineKey = "focus.engineState"
    static let overtimeReminderKey = "focus.overtimeReminder"
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
    private var overtimeReminder = OvertimeReminder()
    private var savedReminderState: [String: Any]?
    private var timer: Timer?
    private var delivery = CueDelivery()
    /// True until the first cue delivery after launch, which drops the start cues of blocks the
    /// loaded engine had already started (the new `CueDelivery` would otherwise re-announce them).
    private var isFirstDelivery = true
    private var cachedSchedule: (fitted: [FittedBlock], meetings: [DateInterval], cues: [ScheduledCue])?
    /// Held while a Focus runs, so App Nap does not coalesce the 1 s timers driving cues and drift.
    private var focusActivity: NSObjectProtocol?

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
        lastAreaID = defaults.string(forKey: Self.lastAreaKey).flatMap(UUID.init(uuidString:)).map { AreaID($0) }
        let storedMinutes = defaults.integer(forKey: Self.lastMinutesKey)
        lastMinutes = FocusEngine.manualMinutes.contains(storedMinutes) ? storedMinutes : 25
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
        if let data = defaults.data(forKey: Self.overtimeReminderKey),
           let stored = try? JSONDecoder().decode(OvertimeReminder.self, from: data) {
            overtimeReminder = stored
            savedReminderState = Self.reminderState(data)
        }
        publish(offer: nil)
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

    /// Whether the running planned Focus is paused because a meeting splits its block.
    func isPaused(at now: Date) -> Bool {
        guard case .planned(let blockID)? = current?.origin,
              let block = todayPlan.state.fitted.first(where: { $0.block.id == blockID }) else { return false }
        return block.isPaused(at: now)
    }

    /// Time focused so far in the running Focus, meeting pauses excluded; 0 when idle.
    func focusedSoFar(at now: Date) -> TimeInterval {
        engine.focusedSoFar(at: now)
    }

    /// How long the running manual Focus has gone past its term; nil otherwise.
    func overtime(at now: Date) -> TimeInterval? {
        engine.overtime(at: now)
    }

    /// The session's area name, "Deleted area" when the area no longer exists.
    func areaName(of session: FocusSession) -> String {
        plan.areaName(for: session.areaID)
    }

    /// The preferred area if it still exists, else the first area.
    var effectiveAreaID: AreaID? {
        if let id = lastAreaID, plan.areas.contains(where: { $0.id == id }) { return id }
        return plan.areas.first?.id
    }

    // MARK: Actions

    func tick() {
        todayPlan.tick()
        let state = todayPlan.state
        let current = now()
        var events = engine.beginDay(state.day, calendar: calendar)
        let loaded = engine
        events += engine.advance(to: current, plan: state.fitted)
        let dayMeetings = meetings.meetings(on: state.day)
        let offer = engine.keepGoingOffer(now: current, plan: state.fitted, meetings: dayMeetings)
        publish(offer: offer)
        persist()
        observeOvertime(at: current)
        var due = delivery.due(schedule(state.fitted, dayMeetings), fitted: state.fitted, now: current)
        if isFirstDelivery {
            due = CueDelivery.dropAlreadyStarted(due, startedBy: loaded)
            isFirstDelivery = false
        }
        due = TakeoverCues.supplement(due: due, events: events, fitted: state.fitted, now: current)
        notifyEnded(events)
        if !due.isEmpty || !events.isEmpty { deliver(due, events, state) }
    }

    func setPreferredMinutes(_ minutes: Int) {
        let range = FocusEngine.manualMinutes
        let clamped = min(max(minutes, range.lowerBound), range.upperBound)
        defaults.set(clamped, forKey: Self.lastMinutesKey)
        lastMinutes = clamped
    }

    func setPreferredArea(_ id: AreaID) {
        defaults.set(id.raw.uuidString, forKey: Self.lastAreaKey)
        lastAreaID = id
    }

    func startManual(areaID: AreaID, intention: String, minutes: Int) {
        setPreferredArea(areaID)
        setPreferredMinutes(minutes)
        apply(engine.startManual(areaID: areaID, intention: intention, minutes: lastMinutes, now: now()))
        draftIntention = ""
    }

    /// Starts with the panel's current values (preferred area, duration and draft intention).
    func startPreferred() {
        guard let area = effectiveAreaID else { return }
        startManual(areaID: area, intention: draftIntention, minutes: lastMinutes)
    }

    /// Five minutes in the preferred area (or the first area) with the draft intention.
    func quickStart() {
        guard let area = effectiveAreaID else { return }
        setPreferredArea(area)
        apply(engine.startManual(areaID: area, intention: draftIntention, minutes: 5, now: now()))
        draftIntention = ""
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
        publish(offer: keepGoing)
        persist()
    }

    // MARK: Helpers

    private func area(for record: FocusRecord) -> Area {
        let id = record.session.areaID
        return plan.areas.first { $0.id == id }
            ?? Area(id: id, name: plan.areaName(for: id), colorIndex: 0, order: 0)
    }

    private func apply(_ events: [FocusEvent]) {
        let state = todayPlan.state
        let offer = engine.keepGoingOffer(
            now: now(), plan: state.fitted, meetings: meetings.meetings(on: state.day)
        )
        publish(offer: offer)
        persist()
        notifyEnded(events)
        if !events.isEmpty { deliver([], events, state) }
    }

    private func notifyEnded(_ events: [FocusEvent]) {
        guard let onFocusEnded else { return }
        for event in events {
            switch event {
            case .ended(let record), .tookOver(let record, _): onFocusEnded(record)
            default: break
            }
        }
    }

    private func publish(offer: KeepGoingOffer?) {
        if current != engine.current {
            current = engine.current
            updateFocusActivity()
        }
        if pendingClosures != engine.pendingClosures { pendingClosures = engine.pendingClosures }
        if breakStartedAt != engine.breakStartedAt { breakStartedAt = engine.breakStartedAt }
        if keepGoing != offer { keepGoing = offer }
    }

    private func updateFocusActivity() {
        if current != nil, focusActivity == nil {
            focusActivity = ProcessInfo.processInfo.beginActivity(
                options: [.userInitiatedAllowingIdleSystemSleep], reason: "Focus running"
            )
        } else if current == nil, let activity = focusActivity {
            ProcessInfo.processInfo.endActivity(activity)
            focusActivity = nil
        }
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

    private func observeOvertime(at time: Date) {
        let session = engine.current
        let fired = overtimeReminder.observe(now: time, session: session)
        persistOvertimeReminder()
        if fired, let session { onOvertimeReminder?(session) }
    }

    /// Writes the reminder only when its state changed; the observation clock alone does not count.
    private func persistOvertimeReminder() {
        guard let data = try? JSONEncoder().encode(overtimeReminder) else { return }
        let state = Self.reminderState(data)
        guard !NSDictionary(dictionary: state ?? [:]).isEqual(to: savedReminderState ?? [:]) else { return }
        defaults.set(data, forKey: Self.overtimeReminderKey)
        savedReminderState = state
    }

    private static func reminderState(_ data: Data) -> [String: Any]? {
        guard var object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        object.removeValue(forKey: "previous")
        return object
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
