import SwiftUI
import DayPlanKit
import DayViewKit

final class TodayModel: ObservableObject {
    @Published var snapshot: TodaySnapshot
    private let session = DayViewNative.shared.create(
        languageCode: Locale.current.language.languageCode?.identifier ?? "en"
    )
    private(set) lazy var meetingSource: MeetingSource = SessionMeetingSource(session: session)
    private var subscription: DayViewSubscription?
    private var timer: Timer?

    init() {
        snapshot = session.currentSnapshot()
        Self.drainLegacyFocus(session)
        snapshot = session.currentSnapshot()
        subscription = session.subscribe { [weak self] snap in
            self?.snapshot = snap
        }
        let t = Timer(timeInterval: 1, repeats: true) { [weak self] _ in
            self?.session.tick()
        }
        RunLoop.main.add(t, forMode: .common)
        timer = t
    }

    /// One-time upgrade drain. The Focus now runs in Swift and the native app no longer drives the
    /// Kotlin Focus, so a Kotlin session left ACTIVE / OVERTIME by an earlier version would stay
    /// open forever: presence keeps sampling, drift keeps badging the Dock with nothing to clear
    /// it, and the focus totals keep growing. Close it once at launch as COMPLETED (the outcome
    /// the controller accepts without an early-exit detour name), then clear any drift reminder
    /// (and its Dock attention) or resume-ritual latch an earlier version left behind: the views
    /// that dismissed them are gone. This is the only place the app calls `closeFocus`; once
    /// drained the Kotlin Focus stays idle, so later launches only re-clear empty latches.
    private static func drainLegacyFocus(_ session: DayViewSession) {
        let snapshot = session.currentSnapshot()
        if snapshot.pomodoroStatus == "ACTIVE" || snapshot.pomodoroStatus == "OVERTIME" {
            session.closeFocus(
                outcome: "COMPLETED", intention: snapshot.focusIntention, detourCategory: "", detourDescription: ""
            )
        }
        session.dismissDriftReminder()
        session.dismissResumeRitual()
    }

    func setDayStart(minutes: Int32) { session.setDayStart(minutes: minutes) }
    func setDayEnd(minutes: Int32) { session.setDayEnd(minutes: minutes) }
    func setShowSeconds(_ enabled: Bool) { session.setShowSeconds(enabled: enabled) }
    func setThemeMode(_ mode: String) { session.setThemeMode(mode: mode) }
    func setNetTimeEnabled(_ enabled: Bool) { session.setNetTimeEnabled(enabled: enabled) }
    func setCalendarIncluded(id: String, included: Bool) { session.setCalendarIncluded(id: id, included: included) }
    func requestCalendarAccess() { session.requestCalendarAccess() }

    func addDetour(category: String, durationMinutes: Int32, description: String) {
        session.addDetour(category: category, durationMinutes: durationMinutes, description: description)
    }
    func updateDetour(index: Int32, startEpochMillis: Int64, endEpochMillis: Int64, category: String, description: String) {
        session.updateDetour(index: index, startEpochMillis: startEpochMillis, endEpochMillis: endEpochMillis, category: category, description: description)
    }
    func removeDetour(index: Int32) { session.removeDetour(index: index) }
    func restoreLastRemovedDetour() { session.restoreLastRemovedDetour() }
    func stopOpenDetour() { session.stopOpenDetour() }
    func forgetRecentDetourCategory(_ category: String) { session.forgetRecentDetourCategory(category: category) }
    func addPlannedObligation(_ label: String) { session.addPlannedObligation(label: label) }
    func removePlannedObligation(_ label: String) { session.removePlannedObligation(label: label) }
    func completePlannedObligation(_ label: String) { session.completePlannedObligation(label: label) }
    func editPlannedObligation(_ oldLabel: String, newLabel: String) {
        session.editPlannedObligation(oldLabel: oldLabel, newLabel: newLabel)
    }

    func openHistory() { session.openHistory() }
    func openHistoryDay(_ dayKey: Int64) { session.openHistoryDay(dayKey: dayKey) }
    func closeHistory() { session.closeHistory() }

    func addOnGoalApp(bundleId: String, name: String) { session.addOnGoalApp(bundleId: bundleId, name: name) }
    func removeOnGoalApp(bundleId: String) { session.removeOnGoalApp(bundleId: bundleId) }
    func onGoalApps() -> [AppRef] { session.onGoalApps() }
    func runningApps() -> [AppRef] { session.runningApps() }
    var onGoalBundleIds: [String] { session.onGoalBundleIds() }

    deinit {
        subscription?.cancel()
        timer?.invalidate()
        session.close()
    }
}
