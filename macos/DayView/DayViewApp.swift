import SwiftUI

@main
struct DayViewApp: App {
    @StateObject private var model = TodayModel()
    @StateObject private var windows = WindowVisibility()

    var body: some Scene {
        Window("DayView", id: "main") {
            MainWindowRoot(model: model, windows: windows)
        }
        MenuBarExtra(menuBarTitle) {
            MenuBarContent(model: model, windows: windows)
        }
        Window("DayView Mini", id: "mini") {
            MiniView(model: model)
                .frame(minWidth: 200, minHeight: 300)
                .onAppear { windows.isMiniOpen = true }
                .onDisappear { windows.isMiniOpen = false }
        }
        .windowLevel(.floating)
        .defaultSize(width: 360, height: 520)
        // Keep the mini out of the Window menu: opening it from there would bypass the
        // open-one-dismiss-the-other swaps and leave both windows open.
        .commandsRemoved()
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

/// Main-window root: RingView plus the compact-mode affordance (mirrors the JVM header
/// control) and this window's visibility reporting.
private struct MainWindowRoot: View {
    @ObservedObject var model: TodayModel
    @ObservedObject var windows: WindowVisibility
    @Environment(\.openWindow) private var openWindow
    @Environment(\.dismissWindow) private var dismissWindow

    var body: some View {
        ZStack(alignment: .topTrailing) {
            RingView(model: model)
            Button {
                openWindow(id: "mini")
                dismissWindow(id: "main")
            } label: {
                Image(systemName: "arrow.down.right.and.arrow.up.left")
            }
            .buttonStyle(.borderless)
            .help("Switch to the mini window")
            .padding(10)
        }
        .frame(minWidth: 420, minHeight: 680)
        .onAppear { windows.isMainOpen = true }
        .onDisappear { windows.isMainOpen = false }
    }
}
