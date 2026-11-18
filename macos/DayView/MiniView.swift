import SwiftUI
import DayViewKit

/// Compact always-on-top companion: ring + countdown, the active area's goal card (when tall
/// enough), the drift reminder, and the compact Focus panel (closure card, running / idle
/// controls). Observes the same models as the main window and the menu bar.
struct MiniView: View {
    @ObservedObject var model: TodayModel
    @ObservedObject var plan: PlanModel
    @ObservedObject var todayPlan: TodayPlanModel
    @ObservedObject var focus: FocusModel
    @ObservedObject var drift: DriftModel
    @Environment(\.openWindow) private var openWindow
    @Environment(\.dismissWindow) private var dismissWindow

    @Environment(\.colorScheme) private var colorScheme
    private var palette: DayViewPalette { DayViewPalette.current(for: colorScheme) }

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .topTrailing) {
                VStack(spacing: 12) {
                    ZStack {
                        DayRingCanvas(
                            momentAngleDegrees: model.snapshot.momentAngleDegrees,
                            remainingRatio: model.snapshot.remainingRatio,
                            isFinished: model.snapshot.isFinished,
                            hasStarted: model.snapshot.hasStarted,
                            hasGoal: focus.activeArea?.hasGoal ?? false,
                            busyArcs: model.snapshot.busyArcs,
                            lineWidth: 12,
                            inset: 20
                        )
                        VStack(spacing: 2) {
                            Text(model.snapshot.dayStatus)
                                .font(.system(size: 24, weight: .light, design: .rounded))
                                .monospacedDigit()
                                .foregroundStyle(palette.cloud)
                            if !model.snapshot.secondsLabel.isEmpty {
                                Text(model.snapshot.secondsLabel).font(.caption2).monospacedDigit().foregroundStyle(palette.muted)
                            }
                            if !model.snapshot.netTimeLabel.isEmpty {
                                Text(model.snapshot.netTimeLabel).font(.caption2).monospacedDigit().foregroundStyle(palette.muted)
                            }
                        }
                    }
                    .frame(maxHeight: .infinity)
                    // Height-gated like the JVM mini (showGoalInMiniWindow: 400 at font scale 1).
                    if proxy.size.height >= 400 {
                        ActiveAreaGoalCard(focus: focus, plan: plan, compact: true)
                    }
                    // The mini runs as an accessory app (no Dock bounce) with the main window
                    // closed, so the drift reminder must show here too.
                    if let areaID = drift.reminderAreaID {
                        DriftBanner(areaName: plan.areaName(for: areaID), onDismiss: drift.dismissReminder)
                    }
                    if model.snapshot.detourOpenRunning {
                        OpenDetourBanner(model: model)
                    }
                    FocusPanel(focus: focus, plan: plan, todayPlan: todayPlan, compact: true)
                }
                .padding(16)
                // Expand back to the full window (mirrors the JVM's expand glyph).
                Button {
                    openWindow(id: "main")
                    dismissWindow(id: "mini")
                } label: {
                    Image(systemName: "arrow.up.left.and.arrow.down.right")
                }
                .buttonStyle(.borderless)
                .help("Open the full window")
                .padding(8)
            }
            .background(
                RadialGradient(
                    gradient: Gradient(colors: [palette.glow, palette.ink]),
                    center: .center, startRadius: 0, endRadius: 500
                )
                .ignoresSafeArea()
            )
        }
    }
}
