import SwiftUI

@main
struct DayViewApp: App {
    @StateObject private var model: TodayModel
    @StateObject private var windows = WindowVisibility()
    @StateObject private var plan: PlanModel
    @StateObject private var todayPlan: TodayPlanModel

    init() {
        let model = TodayModel()
        let plan = PlanModel.live()
        _model = StateObject(wrappedValue: model)
        _plan = StateObject(wrappedValue: plan)
        _todayPlan = StateObject(wrappedValue: TodayPlanModel(plan: plan, meetings: model.meetingSource))
    }

    var body: some Scene {
        Window("DayView", id: "main") {
            MainWindowRoot(model: model, windows: windows, plan: plan, todayPlan: todayPlan)
        }
        .commands {
            CommandMenu("Focus") {
                Button("Start focus") {
                    model.startFocus(intention: model.snapshot.focusIntention)
                }
                .keyboardShortcut(.return, modifiers: .command)
                .disabled(
                    model.snapshot.detourOpenRunning ||
                    model.snapshot.pomodoroStatus == "ACTIVE" ||
                    model.snapshot.pomodoroStatus == "OVERTIME"
                )
                Divider()
                Button("Shorter focus") { model.changePomodoroDuration(-5) }
                    .keyboardShortcut(.leftArrow, modifiers: [])
                    .disabled(
                        model.snapshot.pomodoroStatus == "ACTIVE" ||
                        model.snapshot.pomodoroStatus == "OVERTIME"
                    )
                Button("Longer focus") { model.changePomodoroDuration(5) }
                    .keyboardShortcut(.rightArrow, modifiers: [])
                    .disabled(
                        model.snapshot.pomodoroStatus == "ACTIVE" ||
                        model.snapshot.pomodoroStatus == "OVERTIME"
                    )
            }
        }
        MenuBarExtra(model.snapshot.menuBarTitle) {
            MenuBarContent(model: model, windows: windows, todayPlan: todayPlan)
        }
        Window("DayView Mini", id: "mini") {
            MiniView(model: model)
                .frame(minWidth: 200, minHeight: 300)
                .background(MiniWindowBehavior())
                .onAppear { windows.isMiniOpen = true }
                .onDisappear { windows.isMiniOpen = false }
                .preferredColorScheme(preferredScheme(for: model.snapshot.themeMode))
        }
        .windowLevel(.floating)
        .defaultSize(width: 360, height: 520)
        // Keep the mini out of the Window menu: opening it from there would bypass the
        // open-one-dismiss-the-other swaps and leave both windows open.
        .commandsRemoved()
        Settings {
            SettingsView(model: model, plan: plan)
        }
    }
}

/// Main-window root: RingView plus the compact-mode affordance (mirrors the JVM header
/// control) and this window's visibility reporting.
private struct MainWindowRoot: View {
    @ObservedObject var model: TodayModel
    @ObservedObject var windows: WindowVisibility
    @ObservedObject var plan: PlanModel
    @ObservedObject var todayPlan: TodayPlanModel
    @Environment(\.openWindow) private var openWindow
    @Environment(\.dismissWindow) private var dismissWindow

    var body: some View {
        ZStack(alignment: .topTrailing) {
            RingView(model: model, plan: plan, todayPlan: todayPlan)
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
        .onAppear {
            windows.isMainOpen = true
            todayPlan.mainWindowShown()
        }
        .sheet(isPresented: $todayPlan.isMorningCheckPresented) {
            MorningCheckSheet(todayPlan: todayPlan, areas: plan.areas)
        }
        .onDisappear { windows.isMainOpen = false }
        .preferredColorScheme(preferredScheme(for: model.snapshot.themeMode))
    }
}
