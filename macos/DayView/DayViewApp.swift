import SwiftUI

@main
struct DayViewApp: App {
    @StateObject private var model = TodayModel()

    var body: some Scene {
        Window("DayView", id: "main") {
            RingView(model: model)
                .frame(minWidth: 420, minHeight: 680)
        }
        MenuBarExtra(menuBarTitle) {
            MenuBarContent(model: model)
        }
    }

    // Live menu-bar readout: the focus countdown during a session, otherwise the day's
    // remaining-time headline. Recomputed whenever the model's snapshot ticks.
    private var menuBarTitle: String {
        switch model.snapshot.pomodoroStatus {
        case "ACTIVE", "BREAK": return model.snapshot.pomodoroClock
        default: return model.snapshot.dayStatus
        }
    }
}
