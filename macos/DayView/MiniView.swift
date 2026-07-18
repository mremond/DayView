import SwiftUI
import DayViewKit

/// Compact always-on-top companion: ring + countdown, a display-only goal card, and the
/// full focus card (intention sheet, live clock, stop/relaunch, closure ritual). Mirrors
/// the JVM mini window; observes the same TodayModel as the main window and menu bar.
struct MiniView: View {
    @ObservedObject var model: TodayModel
    @Environment(\.openWindow) private var openWindow
    @Environment(\.dismissWindow) private var dismissWindow
    @State private var showIntentionSheet = false
    @State private var draftIntention = ""

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
                            hasGoal: !model.snapshot.goalTitle.isEmpty || model.snapshot.goalHasDeadline,
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
                        goalCard
                    }
                    focusCard
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
        .sheet(isPresented: $showIntentionSheet) { intentionSheet }
        .onChange(of: model.snapshot.showResumeRitual) { _, showing in
            // A ritual while in mini mode restores full mode (JVM parity), where the
            // ritual panel is rendered.
            if showing {
                openWindow(id: "main")
                dismissWindow(id: "mini")
            }
        }
    }

    private var goalCard: some View {
        HStack {
            Text(model.snapshot.goalTitle.isEmpty ? "No goal yet" : model.snapshot.goalTitle)
                .foregroundStyle(model.snapshot.goalTitle.isEmpty ? Color.secondary : Color.primary)
                .lineLimit(1)
            Spacer()
            if model.snapshot.goalHasDeadline {
                Text("\(model.snapshot.goalHoursRemaining)h left")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .dayViewPanel(palette)
    }

    private var focusCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            switch model.snapshot.pomodoroStatus {
            case "ACTIVE":
                HStack(spacing: 8) {
                    Text("Focus · \(model.snapshot.focusIntention)")
                        .lineLimit(1)
                    Spacer()
                    Text(model.snapshot.pomodoroClock).monospacedDigit()
                    Button("Stop") { model.stopFocus() }
                        .tint(palette.red)
                }
            case "BREAK":
                HStack(spacing: 8) {
                    Text("Break · \(model.snapshot.focusIntention)")
                        .lineLimit(1)
                    Spacer()
                    Text(model.snapshot.pomodoroClock).monospacedDigit()
                    // Relaunch the next session of the sequence, keeping the intention.
                    Button("Relaunch") { model.startFocus(intention: model.snapshot.focusIntention) }
                        .tint(palette.amber)
                    Button("Stop") { model.stopFocus() }
                        .tint(palette.red)
                }
                FocusClosureButtons(model: model)
            default: // "IDLE"
                Button("Start focus") {
                    draftIntention = model.snapshot.focusIntention
                    showIntentionSheet = true
                }
                .tint(palette.amber)
            }
        }
        .dayViewPanel(palette)
    }

    private var intentionSheet: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("What are you focusing on?")
                .font(.headline)
            TextField("Focus intention", text: $draftIntention)
                .textFieldStyle(.roundedBorder)
            HStack {
                Spacer()
                Button("Cancel") { showIntentionSheet = false }
                Button("Start") {
                    model.startFocus(intention: draftIntention)
                    showIntentionSheet = false
                }
                .keyboardShortcut(.defaultAction)
                .disabled(draftIntention.isEmpty)
            }
        }
        .padding(20)
        .frame(width: 320)
    }
}
