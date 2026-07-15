import SwiftUI
import AppKit
import DayViewKit

struct MenuBarContent: View {
    @ObservedObject var model: TodayModel
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Text(model.snapshot.dayStatus)
        if let focusLine {
            Text(focusLine)
        }
        if model.snapshot.goalHasDeadline {
            Text("\(model.snapshot.goalHoursRemaining)h left")
        }
        Divider()
        Button("Open DayView") { openWindow(id: "main") }
        Button("Quit DayView") { NSApplication.shared.terminate(nil) }
    }

    // Mirrors RingView.focusText: "Focus" during an active session, "Break" during
    // the pause (no intention on the break line), nothing when idle.
    private var focusLine: String? {
        let s = model.snapshot
        switch s.pomodoroStatus {
        case "ACTIVE": return "Focus · \(s.focusIntention) · \(s.pomodoroClock)"
        case "BREAK": return "Break · \(s.pomodoroClock)"
        default: return nil
        }
    }
}
