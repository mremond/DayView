import AppKit
import Combine
import DayPlanKit
import DayPlanStore
import Foundation
@preconcurrency import UserNotifications

/// Samples the frontmost app during a Focus and turns drift into a banner, a notification and a
/// Dock bounce, and ignored cues into stored signals. The rules live in `DriftMonitor`.
@MainActor
final class DriftModel: ObservableObject {
    /// The area the visible drift reminder is about; nil when there is none.
    @Published private(set) var reminderAreaID: AreaID?

    private let focus: FocusModel
    private let plan: PlanModel
    private let now: () -> Date
    private let calendar: Calendar
    private var monitor = DriftMonitor()
    /// On-goal presence of the running Focus, for deep-focus stretches; reset when the session changes.
    private var presence = PresenceAccumulator()
    private var presenceSessionID: UUID?
    private var presenceMeasured = false
    /// Deep time already stored for the running session (a Focus reopened by Keep going), read once per session.
    private var storedDeep: TimeInterval?
    private var timer: Timer?
    private var focusSubscription: AnyCancellable?
    private var requestedAuthorization = false
    /// True once the monitor was reset after the Focus ended; sampling then waits for the next Focus.
    private var idleReset = false
    /// A reminder (banner or notification) is out and should clear once the user is back on-goal.
    private var reminderOutstanding = false
    private var screenLocked = false
    private var screensAsleep = false
    private var observers: [(NotificationCenter, NSObjectProtocol)] = []

    /// Each reminder replaces the previous one instead of stacking in Notification Center.
    static let notificationID = "dayview.drift"

    init(
        focus: FocusModel, plan: PlanModel,
        calendar: Calendar = .autoupdatingCurrent, now: @escaping () -> Date = Date.init
    ) {
        self.focus = focus
        self.plan = plan
        self.calendar = calendar
        self.now = now
        focusSubscription = focus.$current.sink { [weak self] session in
            MainActor.assumeIsolated { self?.focusChanged(session) }
        }
        let timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.sampleFrontmostApp() }
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
        observeAwayState()
    }

    deinit {
        timer?.invalidate()
        for (center, token) in observers { center.removeObserver(token) }
    }

    func dismissReminder() {
        reminderAreaID = nil
    }

    /// Opens an ignored-cue watch for each delivered block start (planned or takeover) that was
    /// heard: with cues off the user was told nothing, so nothing can be ignored.
    func cueDelivered(_ cues: [ScheduledCue], cuesEnabled: Bool) {
        guard cuesEnabled else { return }
        for cue in cues where cue.kind == .blockStart {
            guard let areaID = cue.block?.areaID else { continue }
            let measurable = plan.areas.first { $0.id == areaID }?.onGoalApps.isEmpty == false
            monitor.cueDelivered(areaID: areaID, measurable: measurable, at: now())
        }
    }

    // MARK: Deep focus

    /// Whether deep focus is measured for the running Focus (its area has on-goal apps).
    var currentDeepMeasured: Bool {
        presenceMeasured && presenceSessionID == focus.current?.id
    }

    /// The deep-focus stretches of the running Focus so far, the open one included; empty when not measured.
    func liveDeepStretches(at time: Date) -> [DateInterval] {
        currentDeepMeasured ? presence.live(at: time) : []
    }

    /// Deep time of the running Focus so far, counting only stretches that reach the minimum and any
    /// already stored for it (a reopened Focus); nil when not measured.
    func liveDeepTotal(at time: Date) -> TimeInterval? {
        guard let session = focus.current, presenceSessionID == session.id else { return nil }
        guard presenceMeasured else { return storedDeep }
        return (storedDeep ?? 0) + presence.liveTotal(at: time)
    }

    /// Stores the engaged and deep-focus time of a Focus that just ended. `detours` are today's.
    func recordMetrics(for record: FocusRecord, detours: [DateInterval]) {
        let session = record.session
        let end = max(record.actualEnd, session.start)
        let engaged = EngagedTime.compute(
            focused: record.focused, window: DateInterval(start: session.start, end: end), detours: detours
        )
        var deep: TimeInterval?
        var stretches: [DateInterval] = []
        if presenceSessionID == session.id, presenceMeasured {
            stretches = presence.finish(at: end)
            deep = stretches.reduce(0) { $0 + $1.duration }
        }
        let metrics = FocusMetrics(
            sessionID: session.id, areaID: session.areaID, day: LocalDay(containing: session.start, calendar: calendar),
            engaged: engaged, deep: deep, deepStretches: stretches
        )
        // A Focus reopened by Keep going was already saved at the takeover: keep its earlier stretches.
        plan.saveFocusMetrics(metrics.merged(withStored: storedMetrics(for: session)))
    }

    private func storedMetrics(for session: FocusSession) -> FocusMetrics? {
        plan.focusMetrics(on: LocalDay(containing: session.start, calendar: calendar))[session.id]
    }

    private func resetPresenceIfNeeded(for session: FocusSession) {
        guard presenceSessionID != session.id else { return }
        presence = PresenceAccumulator()
        presenceSessionID = session.id
        presenceMeasured = focus.activeArea?.onGoalApps.isEmpty == false
        storedDeep = storedMetrics(for: session)?.deep
    }

    // MARK: Sampling

    private func sampleFrontmostApp() {
        guard let session = focus.current else {
            // One nil sample resets the monitor; no frontmost-app queries until a Focus runs again.
            if !idleReset { _ = monitor.sample(.neutral, areaID: nil, at: now()) }
            idleReset = true
            return
        }
        idleReset = false
        let time = now()
        resetPresenceIfNeeded(for: session)
        let classification = classify(at: time)
        if presenceMeasured { presence.sample(onGoal: classification == .onGoal, at: time) }
        if classification == .onGoal { clearReminder() }
        for event in monitor.sample(classification, areaID: session.areaID, at: time) {
            handle(event)
        }
    }

    /// Neutral while the planned block is paused for a meeting or the user is away (screen locked
    /// or asleep); otherwise the frontmost app against the active area.
    private func classify(at time: Date) -> AppClassification {
        guard !screenLocked, !screensAsleep, !focus.isPaused(at: time), let area = focus.activeArea else { return .neutral }
        return AppClassifier.classify(bundleID: NSWorkspace.shared.frontmostApplication?.bundleIdentifier, area: area)
    }

    private func observeAwayState() {
        let distributed = DistributedNotificationCenter.default()
        let workspace = NSWorkspace.shared.notificationCenter
        let watch: [(NotificationCenter, Notification.Name, @MainActor (DriftModel) -> Void)] = [
            (distributed, Notification.Name("com.apple.screenIsLocked"), { $0.screenLocked = true }),
            (distributed, Notification.Name("com.apple.screenIsUnlocked"), { $0.screenLocked = false }),
            (workspace, NSWorkspace.screensDidSleepNotification, { $0.screensAsleep = true }),
            (workspace, NSWorkspace.screensDidWakeNotification, { $0.screensAsleep = false }),
        ]
        for (center, name, apply) in watch {
            let token = center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated {
                    guard let self else { return }
                    apply(self)
                }
            }
            observers.append((center, token))
        }
    }

    private func handle(_ event: DriftEvent) {
        switch event {
        case .driftReminder(let areaID):
            remind(areaID: areaID)
        case .cueIgnored(let areaID, let cueAt):
            plan.saveIgnoredCue(areaID: areaID, at: cueAt, calendar: calendar)
        }
    }

    private func remind(areaID: AreaID) {
        reminderAreaID = areaID
        reminderOutstanding = true
        postNotification(areaName: plan.areaName(for: areaID), intention: focus.current?.intention ?? "")
        NSApp.requestUserAttention(.informationalRequest)
    }

    private func focusChanged(_ session: FocusSession?) {
        if let session {
            requestAuthorizationOnce()
            if let id = reminderAreaID, id != session.areaID { clearReminder() }
        } else {
            clearReminder()
        }
    }

    /// Back on-goal: the banner and the delivered notification are stale.
    private func clearReminder() {
        guard reminderOutstanding else { return }
        reminderOutstanding = false
        reminderAreaID = nil
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [Self.notificationID])
    }

    // MARK: Notifications

    private func requestAuthorizationOnce() {
        guard !requestedAuthorization else { return }
        requestedAuthorization = true
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    private func postNotification(areaName: String, intention: String) {
        let center = UNUserNotificationCenter.current()
        let title = LF("You planned %@", areaName)
        let identifier = Self.notificationID
        let body = intention.trimmingCharacters(in: .whitespacesAndNewlines)
        center.getNotificationSettings { settings in
            guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else { return }
            let content = UNMutableNotificationContent()
            content.title = title
            content.body = body
            let request = UNNotificationRequest(identifier: identifier, content: content, trigger: nil)
            center.add(request) { _ in }
        }
    }
}
