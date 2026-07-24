import SwiftUI
import DayViewKit

final class TodayModel: ObservableObject {
    @Published var snapshot: TodaySnapshot
    private let session = DayViewNative.shared.create()
    private var subscription: DayViewSubscription?
    private var timer: Timer?

    init() {
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

    func closeFocus(_ outcome: String, intention: String, detourCategory: String, detourDescription: String) {
        session.closeFocus(outcome: outcome, intention: intention, detourCategory: detourCategory, detourDescription: detourDescription)
    }
    func setDayStart(minutes: Int32) { session.setDayStart(minutes: minutes) }
    func setDayEnd(minutes: Int32) { session.setDayEnd(minutes: minutes) }
    func setShowSeconds(_ enabled: Bool) { session.setShowSeconds(enabled: enabled) }
    func setThemeMode(_ mode: String) { session.setThemeMode(mode: mode) }
    func setNetTimeEnabled(_ enabled: Bool) { session.setNetTimeEnabled(enabled: enabled) }
    func setCalendarIncluded(id: String, included: Bool) { session.setCalendarIncluded(id: id, included: included) }
    func requestCalendarAccess() { session.requestCalendarAccess() }
    func setFocusIntention(_ text: String) { session.setFocusIntention(intention: text) }
    func changePomodoroDuration(_ delta: Int32) { session.changePomodoroDuration(deltaMinutes: delta) }
    func startFocus(intention: String) { session.startFocus(intention: intention) }
    func setGoalTitle(_ title: String) { session.setGoalTitle(title: title) }
    func setGoalDeadline(epochMillis: Int64) { session.setGoalDeadline(epochMillis: epochMillis) }
    func clearGoalDeadline() { session.setGoalDeadline(epochMillis: 0) }

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

    func addOnGoalApp(bundleId: String, name: String) { session.addOnGoalApp(bundleId: bundleId, name: name) }
    func removeOnGoalApp(bundleId: String) { session.removeOnGoalApp(bundleId: bundleId) }
    func onGoalApps() -> [AppRef] { session.onGoalApps() }
    func runningApps() -> [AppRef] { session.runningApps() }
    var onGoalBundleIds: [String] { session.onGoalBundleIds() }

    func dismissDriftReminder() { session.dismissDriftReminder() }
    func dismissResumeRitual() { session.dismissResumeRitual() }

    deinit {
        subscription?.cancel()
        timer?.invalidate()
        session.close()
    }
}
