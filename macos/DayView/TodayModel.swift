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
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.session.tick()
        }
    }

    func startFocus() { session.startFocus(intention: "Ship it") }
    func stopFocus() { session.stopFocus() }

    deinit {
        subscription?.cancel()
        timer?.invalidate()
        session.close()
    }
}
