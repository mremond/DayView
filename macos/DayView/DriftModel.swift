import AppKit
import Combine
import DayPlanKit
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
    private var timer: Timer?
    private var focusSubscription: AnyCancellable?
    private var requestedAuthorization = false

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
    }

    deinit { timer?.invalidate() }

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

    // MARK: Sampling

    private func sampleFrontmostApp() {
        let bundleID = NSWorkspace.shared.frontmostApplication?.bundleIdentifier
        let classification = focus.activeArea.map { AppClassifier.classify(bundleID: bundleID, area: $0) } ?? .neutral
        for event in monitor.sample(classification, areaID: focus.current?.areaID, at: now()) {
            handle(event)
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
        postNotification(areaName: plan.areaName(for: areaID), intention: focus.current?.intention ?? "")
        NSApp.requestUserAttention(.informationalRequest)
    }

    private func focusChanged(_ session: FocusSession?) {
        if let session {
            requestAuthorizationOnce()
            if let id = reminderAreaID, id != session.areaID { reminderAreaID = nil }
        } else {
            reminderAreaID = nil
        }
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
        let body = intention.trimmingCharacters(in: .whitespacesAndNewlines)
        center.getNotificationSettings { settings in
            guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else { return }
            let content = UNMutableNotificationContent()
            content.title = title
            content.body = body
            let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
            center.add(request) { _ in }
        }
    }
}
