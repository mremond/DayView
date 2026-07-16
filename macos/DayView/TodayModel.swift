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

    func stopFocus() { session.stopFocus() }
    func closeFocus(_ outcome: String) { session.closeFocus(outcome: outcome) }
    func setDayStart(minutes: Int32) { session.setDayStart(minutes: minutes) }
    func setDayEnd(minutes: Int32) { session.setDayEnd(minutes: minutes) }
    func setShowSeconds(_ enabled: Bool) { session.setShowSeconds(enabled: enabled) }
    func setThemeMode(_ mode: String) { session.setThemeMode(mode: mode) }
    func setFocusIntention(_ text: String) { session.setFocusIntention(intention: text) }
    func changePomodoroDuration(_ delta: Int32) { session.changePomodoroDuration(deltaMinutes: delta) }
    func startFocus(intention: String) { session.startFocus(intention: intention) }
    func setGoalTitle(_ title: String) { session.setGoalTitle(title: title) }
    func setGoalDeadline(epochMillis: Int64) { session.setGoalDeadline(epochMillis: epochMillis) }
    func clearGoalDeadline() { session.setGoalDeadline(epochMillis: 0) }

    deinit {
        subscription?.cancel()
        timer?.invalidate()
        session.close()
    }
}
