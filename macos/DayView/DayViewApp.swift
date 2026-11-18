import SwiftUI
@preconcurrency import UserNotifications

@main
struct DayViewApp: App {
    @StateObject private var model: TodayModel
    @StateObject private var windows = WindowVisibility()
    @StateObject private var plan: PlanModel
    @StateObject private var todayPlan: TodayPlanModel
    @StateObject private var focus: FocusModel
    @StateObject private var speaker: CueSpeaker
    @StateObject private var drift: DriftModel

    init() {
        let model = TodayModel()
        let plan = PlanModel.live()
        _model = StateObject(wrappedValue: model)
        _plan = StateObject(wrappedValue: plan)
        let todayPlan = TodayPlanModel(plan: plan, meetings: model.meetingSource)
        _todayPlan = StateObject(wrappedValue: todayPlan)
        let focus = FocusModel(todayPlan: todayPlan, plan: plan, meetings: model.meetingSource)
        let speaker = CueSpeaker()
        let drift = DriftModel(focus: focus, plan: plan)
        // Assigned right after creation so the buffered launch-time cue is delivered at once.
        focus.onCues = { [weak plan] due, events, _ in
            speaker.speak(due: due, events: events, areas: plan?.areas ?? [])
            drift.cueDelivered(due, cuesEnabled: speaker.isEnabled)
        }
        focus.onOvertimeReminder = { [weak plan] session in
            OvertimeNotification.post(areaName: plan?.areaName(for: session.areaID) ?? "")
            speaker.chime()
        }
        _focus = StateObject(wrappedValue: focus)
        _drift = StateObject(wrappedValue: drift)
        _speaker = StateObject(wrappedValue: speaker)
    }

    private var focusRunning: Bool { focus.current != nil }

    var body: some Scene {
        Window("DayView", id: "main") {
            MainWindowRoot(model: model, windows: windows, plan: plan, todayPlan: todayPlan, focus: focus, drift: drift)
        }
        .commands {
            CommandMenu("Focus") {
                Button("Start focus") { focus.startPreferred() }
                    .keyboardShortcut(.return, modifiers: .command)
                    .disabled(focusRunning || focus.effectiveAreaID == nil)
                Divider()
                Button("Shorter focus") { focus.setPreferredMinutes(focus.lastMinutes - 5) }
                    .keyboardShortcut(.leftArrow, modifiers: [])
                    .disabled(focusRunning)
                Button("Longer focus") { focus.setPreferredMinutes(focus.lastMinutes + 5) }
                    .keyboardShortcut(.rightArrow, modifiers: [])
                    .disabled(focusRunning)
            }
        }
        MenuBarExtra(focus.clockText(at: Date()) ?? model.snapshot.dayStatus) {
            MenuBarContent(model: model, windows: windows, todayPlan: todayPlan, focus: focus)
        }
        Window(L("This week"), id: "week") {
            WeekView(plan: plan, focus: focus)
        }
        .defaultSize(width: 420, height: 520)
        Window("DayView Mini", id: "mini") {
            MiniView(model: model, plan: plan, todayPlan: todayPlan, focus: focus, drift: drift)
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
            SettingsView(model: model, plan: plan, speaker: speaker)
        }
    }
}

/// The closing suggestion posted when a manual Focus reaches double its planned time.
enum OvertimeNotification {
    static let identifier = "dayview.overtime"

    static func post(areaName: String) {
        let center = UNUserNotificationCenter.current()
        let title = LF("Still on %@? Close this focus when you're ready.", areaName)
        center.getNotificationSettings { settings in
            guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else { return }
            let content = UNMutableNotificationContent()
            content.title = title
            center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil)) { _ in }
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
    @ObservedObject var focus: FocusModel
    @ObservedObject var drift: DriftModel
    @Environment(\.openWindow) private var openWindow
    @Environment(\.dismissWindow) private var dismissWindow

    var body: some View {
        ZStack(alignment: .topTrailing) {
            RingView(model: model, plan: plan, todayPlan: todayPlan, focus: focus, drift: drift)
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
                .id(todayPlan.state.day)
        }
        .onDisappear { windows.isMainOpen = false }
        .preferredColorScheme(preferredScheme(for: model.snapshot.themeMode))
    }
}
