import SwiftUI
import Foundation
import DayViewKit

struct RingView: View {
    @ObservedObject var model: TodayModel
    @State private var intention: String = ""
    @State private var goalTitle: String = ""
    @State private var deadline: Date = Date()
    @State private var seeded = false
    @State private var lastPomodoroStatus = "IDLE"

    private struct HoveredBusyArc {
        let label: String
        let position: CGPoint
    }

    @State private var hoveredBusy: HoveredBusyArc?

    var body: some View {
        ScrollView {
            VStack(spacing: 28) {
                ringSection
                focusSection
                goalSection
            }
            .padding(32)
        }
        .onReceive(model.$snapshot) { snap in
            // Seed the local text/date fields once from persisted state.
            if !seeded {
                intention = snap.focusIntention
                goalTitle = snap.goalTitle
                if snap.goalHasDeadline {
                    deadline = Date(timeIntervalSince1970: Double(snap.goalDeadlineEpochMillis) / 1000)
                }
                seeded = true
            }
            // A session just ended (closure or stop): the persisted intention is the
            // source of truth — Completed/Progressed cleared it, Resume later kept it —
            // so re-sync the field. Scoped to the non-IDLE -> IDLE transition so it
            // never clobbers mid-typing edits during normal ticks.
            if lastPomodoroStatus != "IDLE" && snap.pomodoroStatus == "IDLE" {
                intention = snap.focusIntention
            }
            lastPomodoroStatus = snap.pomodoroStatus
        }
    }

    private var ringSection: some View {
        VStack(spacing: 8) {
            GeometryReader { proxy in
                DayRingCanvas(
                    momentAngleDegrees: model.snapshot.momentAngleDegrees,
                    busyArcs: model.snapshot.busyArcs
                )
                .onContinuousHover(coordinateSpace: .local) { phase in
                    switch phase {
                    case .active(let point):
                        hoveredBusy = busyArcHit(at: point, in: proxy.size)
                    case .ended:
                        hoveredBusy = nil
                    }
                }
                .overlay(alignment: .topLeading) {
                    if let hover = hoveredBusy {
                        Text(hover.label)
                            .font(.caption)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 6))
                            .offset(x: hover.position.x + 12, y: hover.position.y - 28)
                            .allowsHitTesting(false)
                    }
                }
            }
            .frame(height: 260)
            Text(model.snapshot.dayStatus)
                .font(.system(size: 40, weight: .semibold, design: .rounded))
                .monospacedDigit()
            if !model.snapshot.secondsLabel.isEmpty {
                Text(model.snapshot.secondsLabel)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
            if !model.snapshot.netTimeLabel.isEmpty {
                Text(model.snapshot.netTimeLabel)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
        }
    }

    private var focusSection: some View {
        GroupBox("Focus") {
            VStack(alignment: .leading, spacing: 12) {
                TextField("What are you focusing on?", text: $intention)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit { model.setFocusIntention(intention) }
                HStack {
                    Stepper("Duration: \(model.snapshot.pomodoroMinutes) min",
                            onIncrement: { model.changePomodoroDuration(5) },
                            onDecrement: { model.changePomodoroDuration(-5) })
                        .disabled(model.snapshot.pomodoroStatus != "IDLE")
                    Spacer()
                    switch model.snapshot.pomodoroStatus {
                    case "IDLE":
                        Button("Start focus") {
                            model.startFocus(intention: intention)
                        }
                        .disabled(intention.isEmpty)
                    case "BREAK":
                        // Relaunch the next session of the sequence, keeping the intention.
                        Button("Relaunch") { model.startFocus(intention: model.snapshot.focusIntention) }
                        Button("Stop focus") { model.stopFocus() }
                    default: // "ACTIVE"
                        Button("Stop focus") { model.stopFocus() }
                    }
                }
                Text(model.snapshot.focusLine.isEmpty ? "Idle" : model.snapshot.focusLine)
                    .foregroundStyle(.secondary)
                if model.snapshot.pomodoroStatus == "BREAK" {
                    closureSection
                }
            }
        }
    }

    // The closure ritual: name how the sequence ends so the session record and
    // clean-session ledger stay honest. Break-only; Stop stays an outcome-less abort.
    private var closureSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Close this focus")
                .font(.caption)
                .foregroundStyle(.secondary)
            FocusClosureButtons(model: model)
        }
    }

    private var goalSection: some View {
        GroupBox("Long-term goal") {
            VStack(alignment: .leading, spacing: 12) {
                TextField("Long-term goal", text: $goalTitle)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit { model.setGoalTitle(goalTitle) }
                HStack {
                    DatePicker("Deadline", selection: $deadline)
                        .onChange(of: deadline) { newValue in
                            let millis = Int64(newValue.timeIntervalSince1970 * 1000)
                            // Only persist a genuine user change. Seeding `deadline` from the
                            // snapshot also fires .onChange (with `seeded` already true); guard
                            // on the value so that seed round-trip isn't written back.
                            if seeded && millis != model.snapshot.goalDeadlineEpochMillis {
                                model.setGoalDeadline(epochMillis: millis)
                            }
                        }
                    if model.snapshot.goalHasDeadline {
                        Button("Clear") { model.clearGoalDeadline() }
                    }
                }
                if model.snapshot.goalHasDeadline {
                    Text("\(model.snapshot.goalHoursRemaining)h of working time left")
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    // Geometry half of the hit test (the angle-containment half is Kotlin's
    // busyArcIndexAt): pointer -> polar, radius must sit on the busy lane band.
    // Constants come from DayRingCanvas so drawing and hit-testing cannot drift.
    private func busyArcHit(at point: CGPoint, in size: CGSize) -> HoveredBusyArc? {
        let arcs = model.snapshot.busyArcs
        guard !arcs.isEmpty else { return nil }
        let inset: CGFloat = 40
        let lineWidth: CGFloat = 18
        let side = min(size.width, size.height) - inset * 2
        let radius = max(side / 2, 1)
        let busyRadius = radius - lineWidth * DayRingCanvas.busyRadiusFactor
        let busyWidth = lineWidth * DayRingCanvas.busyWidthFactor
        let center = CGPoint(x: size.width / 2, y: size.height / 2)
        let dx = point.x - center.x
        let dy = point.y - center.y
        let distance = (dx * dx + dy * dy).squareRoot()
        guard abs(distance - busyRadius) <= busyWidth / 2 + 6 else { return nil }
        // y-down atan2 yields the same clockwise-from-3-o'clock convention the arcs use.
        let angle = Double(atan2(dy, dx)) * 180.0 / .pi
        let index = Int(TodaySnapshotKt.busyArcIndexAt(arcs: arcs, angleDegrees: angle))
        guard index >= 0, index < arcs.count else { return nil }
        return HoveredBusyArc(label: arcs[index].hoverLabel, position: point)
    }
}
