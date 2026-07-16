import SwiftUI
import AppKit
import DayViewKit

struct MenuBarContent: View {
    @ObservedObject var model: TodayModel
    @ObservedObject var windows: WindowVisibility
    @Environment(\.openWindow) private var openWindow
    @Environment(\.dismissWindow) private var dismissWindow

    var body: some View {
        Text(model.snapshot.dayStatus)
        if !model.snapshot.focusLine.isEmpty {
            Text(model.snapshot.focusLine)
        }
        if model.snapshot.goalHasDeadline {
            Text("\(model.snapshot.goalHoursRemaining)h left")
        }
        Divider()
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
        Button("Quit DayView") { NSApplication.shared.terminate(nil) }
    }
}
