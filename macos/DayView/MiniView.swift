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

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .topTrailing) {
                VStack(spacing: 12) {
                    VStack(spacing: 4) {
                        DayRingCanvas(momentAngleDegrees: model.snapshot.momentAngleDegrees, lineWidth: 12, inset: 20)
                            .frame(maxHeight: .infinity)
                        Text(model.snapshot.dayStatus)
                            .font(.system(size: 24, weight: .semibold, design: .rounded))
                            .monospacedDigit()
                    }
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
        }
        .sheet(isPresented: $showIntentionSheet) { intentionSheet }
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
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 10))
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
                }
            case "BREAK":
                HStack(spacing: 8) {
                    Text("Break · \(model.snapshot.focusIntention)")
                        .lineLimit(1)
                    Spacer()
                    Text(model.snapshot.pomodoroClock).monospacedDigit()
                    // Relaunch the next session of the sequence, keeping the intention.
                    Button("Relaunch") { model.startFocus(intention: model.snapshot.focusIntention) }
                    Button("Stop") { model.stopFocus() }
                }
                HStack(spacing: 8) {
                    Button("Completed") { model.closeFocus("COMPLETED") }
                        .buttonStyle(.bordered)
                        .tint(.green)
                    Button("Progressed") { model.closeFocus("PROGRESSED") }
                        .buttonStyle(.bordered)
                        .tint(.orange)
                    Button("Resume later") { model.closeFocus("TO_RESUME") }
                        .buttonStyle(.bordered)
                }
            default: // "IDLE"
                Button("Start focus") {
                    draftIntention = model.snapshot.focusIntention
                    showIntentionSheet = true
                }
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 10))
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
