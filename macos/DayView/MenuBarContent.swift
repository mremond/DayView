import SwiftUI
import AppKit
import DayViewKit

struct MenuBarContent: View {
    @ObservedObject var model: TodayModel
    @ObservedObject var windows: WindowVisibility
    @ObservedObject var todayPlan: TodayPlanModel
    @ObservedObject var focus: FocusModel
    @Environment(\.openWindow) private var openWindow
    @Environment(\.dismissWindow) private var dismissWindow

    var body: some View {
        Text(model.snapshot.dayStatus)
        if let line = focus.menuLine(at: Date()) {
            Text(line)
            if focus.keepGoing != nil {
                Button("Keep going (+15 min)") { focus.keepGoingNow() }
            }
            Button("Stop focus") { focus.stop() }
        }
        if !focus.pendingClosures.isEmpty {
            Button("Close a focus…") {
                openWindow(id: "main")
                dismissWindow(id: "mini")
                NSApplication.shared.activate(ignoringOtherApps: true)
            }
        }
        Divider()
        Button("History") {
            model.openHistory()
            openWindow(id: "main")
            dismissWindow(id: "mini")
        }
        Button(L("This week")) {
            openWindow(id: "week")
            NSApplication.shared.activate(ignoringOtherApps: true)
        }
        if windows.isMiniOpen {
            Button("Open full window") {
                openWindow(id: "main")
                dismissWindow(id: "mini")
            }
        } else {
            Button("Open DayView") { openWindow(id: "main") }
            Button("Show mini window") {
                openWindow(id: "mini")
                dismissWindow(id: "main")
            }
        }
        Button(L("Plan today…")) {
            openWindow(id: "main")
            dismissWindow(id: "mini")
            todayPlan.presentMorningCheck()
        }
        Button("Quit DayView") { NSApplication.shared.terminate(nil) }
    }
}
