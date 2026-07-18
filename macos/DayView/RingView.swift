import SwiftUI
import Foundation
import AppKit
import DayViewKit

struct RingView: View {
    @ObservedObject var model: TodayModel
    @State private var intention: String = ""
    @State private var goalTitle: String = ""
    @State private var deadline: Date = Date()
    @State private var seeded = false
    @State private var lastPomodoroStatus = "IDLE"
    @State private var showDetourCapture = false
    @State private var showDetourList = false

    private struct HoveredArc {
        let label: String
        let position: CGPoint
    }

    @State private var hoveredArc: HoveredArc?

    @Environment(\.colorScheme) private var colorScheme
    private var palette: DayViewPalette { DayViewPalette.current(for: colorScheme) }
    @Environment(\.openWindow) private var openWindow
    @Environment(\.dismissWindow) private var dismissWindow

    var body: some View {
        ScrollView {
            VStack(spacing: 28) {
                if model.snapshot.showResumeRitual {
                    resumeRitual
                } else if model.snapshot.showDriftReminder {
                    driftBanner
                }
                ringSection
                detourSection
                focusSection
                goalSection
            }
            .padding(32)
        }
        .background(
            RadialGradient(
                gradient: Gradient(colors: [palette.glow, palette.ink]),
                center: .center, startRadius: 0, endRadius: 500
            )
            .ignoresSafeArea()
        )
        .sheet(isPresented: $showDetourCapture) { DetourCaptureSheet(model: model, isPresented: $showDetourCapture) }
        .sheet(isPresented: $showDetourList) { DetourListSheet(model: model, isPresented: $showDetourList) }
        .onChange(of: model.snapshot.showResumeRitual) { _, showing in
            // The ritual is deliberately interruptive: surface the window it lives in.
            if showing { NSApplication.shared.activate(ignoringOtherApps: true) }
        }
        .onAppear {
            // The window may be created with a ritual already pending (the mini→main swap):
            // onChange does not fire for an initial value, so surface it here too.
            if model.snapshot.showResumeRitual { NSApplication.shared.activate(ignoringOtherApps: true) }
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
        GeometryReader { proxy in
            ZStack {
                DayRingCanvas(
                    momentAngleDegrees: model.snapshot.momentAngleDegrees,
                    remainingRatio: model.snapshot.remainingRatio,
                    isFinished: model.snapshot.isFinished,
                    hasStarted: model.snapshot.hasStarted,
                    hasGoal: !model.snapshot.goalTitle.isEmpty || model.snapshot.goalHasDeadline,
                    busyArcs: model.snapshot.busyArcs,
                    detourBodies: model.snapshot.detourBodies,
                    focusArcs: model.snapshot.focusArcs,
                    focusSessionBands: model.snapshot.focusSessionBands
                )
                .onContinuousHover(coordinateSpace: .local) { phase in
                    switch phase {
                    case .active(let point):
                        hoveredArc = arcHit(at: point, in: proxy.size)
                    case .ended:
                        hoveredArc = nil
                    }
                }
                VStack(spacing: 2) {
                    Text(model.snapshot.dayStatus)
                        .font(.system(size: 40, weight: .light, design: .rounded))
                        .monospacedDigit()
                        .foregroundStyle(palette.cloud)
                    if !model.snapshot.secondsLabel.isEmpty {
                        Text(model.snapshot.secondsLabel)
                            .font(.caption).monospacedDigit().foregroundStyle(palette.muted)
                    }
                    if !model.snapshot.netTimeLabel.isEmpty {
                        Text(model.snapshot.netTimeLabel)
                            .font(.caption).monospacedDigit().foregroundStyle(palette.muted)
                    }
                    if !model.snapshot.detourTotalLabel.isEmpty {
                        Text(model.snapshot.detourTotalLabel)
                            .font(.caption).foregroundStyle(palette.muted)
                    }
                    if !model.snapshot.focusTotalLabel.isEmpty {
                        Text(model.snapshot.focusTotalLabel)
                            .font(.caption).foregroundStyle(palette.mint)
                    }
                }
                if let hover = hoveredArc {
                    Text(hover.label)
                        .font(.caption)
                        .padding(.horizontal, 8).padding(.vertical, 4)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 6))
                        .position(x: hover.position.x + 12, y: hover.position.y - 28)
                        .allowsHitTesting(false)
                }
            }
        }
        .frame(height: 300)
    }

    private var detourSection: some View {
        VStack(spacing: 10) {
            if !model.snapshot.detourSources.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(model.snapshot.detourSources, id: \.label) { source in
                            Button {
                                showDetourList = true
                            } label: {
                                HStack(spacing: 6) {
                                    Circle().fill(palette.detourColor(Int(source.colorIndex))).frame(width: 8, height: 8)
                                    Text(source.label).foregroundStyle(palette.cloud)
                                    Text(source.totalLabel).foregroundStyle(palette.muted)
                                }
                                .font(.caption)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            Button {
                showDetourCapture = true
            } label: {
                Label("Detour", systemImage: "plus")
            }
            .buttonStyle(.bordered)
            .tint(palette.muted)
        }
    }

    private var focusSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("FOCUS")
                .font(.caption2).bold().kerning(1.2).foregroundStyle(palette.amber)
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
                    .tint(palette.amber)
                    .disabled(intention.isEmpty)
                case "BREAK":
                    // Relaunch the next session of the sequence, keeping the intention.
                    Button("Relaunch") { model.startFocus(intention: model.snapshot.focusIntention) }
                        .tint(palette.amber)
                    Button("Stop focus") { model.stopFocus() }
                        .tint(palette.red)
                default: // "ACTIVE"
                    Button("Stop focus") { model.stopFocus() }
                        .tint(palette.red)
                }
            }
            Text(model.snapshot.focusLine.isEmpty ? "Idle" : model.snapshot.focusLine)
                .foregroundStyle(.secondary)
            if model.snapshot.pomodoroStatus == "BREAK" {
                closureSection
            }
        }
        .dayViewPanel(palette)
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

    // Drift nudge: an amber panel restating the intention, dismissable in one click.
    private var driftBanner: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("BACK TO THE ESSENTIAL")
                .font(.caption2).bold().kerning(1.2).foregroundStyle(palette.amber)
            Text(model.snapshot.focusIntention.isEmpty ? "One thing at a time." : model.snapshot.focusIntention)
                .foregroundStyle(palette.cloud)
            HStack {
                Spacer()
                Button("BACK AT IT") { model.dismissDriftReminder() }
                    .buttonStyle(.bordered)
                    .tint(palette.amber)
            }
        }
        .dayViewPanel(palette)
    }

    // Resumption ritual: a still-running session found at launch or after a wake.
    private var resumeRitual: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("YOUR RESUME POINT")
                .font(.caption2).bold().kerning(1.2).foregroundStyle(palette.mint)
            Text(model.snapshot.focusIntention.isEmpty ? "One thing at a time." : model.snapshot.focusIntention)
                .foregroundStyle(palette.cloud)
            if !model.snapshot.pomodoroClock.isEmpty {
                Text("\(model.snapshot.pomodoroClock) left to stay on track.")
                    .font(.caption).foregroundStyle(palette.muted)
            }
            HStack {
                Spacer()
                Button("Stop") { model.stopFocus(); model.dismissResumeRitual() }
                    .buttonStyle(.bordered)
                    .tint(palette.red)
                Button("Resume") { model.dismissResumeRitual() }
                    .buttonStyle(.bordered)
                    .tint(palette.mint)
            }
        }
        .dayViewPanel(palette)
    }

    private var goalSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("LONG-TERM GOAL")
                .font(.caption2).bold().kerning(1.2).foregroundStyle(palette.mint)
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
        .dayViewPanel(palette)
    }

    // Geometry half of the hit test (angular containment is Kotlin's angularArcIndexAt):
    // pointer -> polar, then whichever lane band the radius sits on. Detour rides an outer
    // lane, busy an inner one; constants come from DayRingCanvas so drawing and hit-testing
    // cannot drift.
    private func arcHit(at point: CGPoint, in size: CGSize) -> HoveredArc? {
        let inset = DayRingCanvas.defaultInset
        let lineWidth = DayRingCanvas.defaultLineWidth
        let side = min(size.width, size.height) - inset * 2
        let radius = max(side / 2, 1)
        let center = CGPoint(x: size.width / 2, y: size.height / 2)
        let dx = point.x - center.x
        let dy = point.y - center.y
        let distance = (dx * dx + dy * dy).squareRoot()
        // y-down atan2 yields the same clockwise-from-3-o'clock convention the arcs use.
        let angle = Double(atan2(dy, dx)) * 180.0 / .pi
        let bandHalf = lineWidth * DayRingCanvas.busyWidthFactor / 2 + 6

        // Detour lane (outer) first.
        let detours = model.snapshot.detourBodies
        let detourRadius = radius + lineWidth * DayRingCanvas.detourRadiusFactor
        if !detours.isEmpty, abs(distance - detourRadius) <= bandHalf {
            let index = Int(TodaySnapshotKt.detourBodyIndexAt(bodies: detours, angleDegrees: angle))
            if index >= 0, index < detours.count {
                return HoveredArc(label: detours[index].hoverLabel, position: point)
            }
        }

        // Busy lane (inner).
        let arcs = model.snapshot.busyArcs
        let busyRadius = radius - lineWidth * DayRingCanvas.busyRadiusFactor
        if !arcs.isEmpty, abs(distance - busyRadius) <= bandHalf {
            let index = Int(TodaySnapshotKt.busyArcIndexAt(arcs: arcs, angleDegrees: angle))
            if index >= 0, index < arcs.count {
                return HoveredArc(label: arcs[index].hoverLabel, position: point)
            }
        }
        return nil
    }
}
