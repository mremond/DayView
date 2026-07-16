import SwiftUI
import DayViewKit

struct RingView: View {
    @ObservedObject var model: TodayModel
    @State private var intention: String = ""
    @State private var goalTitle: String = ""
    @State private var deadline: Date = Date()
    @State private var seeded = false
    @State private var lastPomodoroStatus = "IDLE"

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
            Canvas { context, size in
                let inset: CGFloat = 40
                let side = min(size.width, size.height) - inset * 2
                let center = CGPoint(x: size.width / 2, y: size.height / 2)
                let radius = max(side / 2, 1)
                let lineWidth: CGFloat = 18
                var track = Path()
                track.addArc(center: center, radius: radius, startAngle: .degrees(0), endAngle: .degrees(360), clockwise: false)
                context.stroke(track, with: .color(.gray.opacity(0.2)), lineWidth: lineWidth)
                var sweep = Path()
                sweep.addArc(center: center, radius: radius, startAngle: .degrees(-90), endAngle: .degrees(model.snapshot.momentAngleDegrees), clockwise: false)
                context.stroke(sweep, with: .color(.accentColor), style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
            }
            .frame(height: 260)
            Text(model.snapshot.dayStatus)
                .font(.system(size: 40, weight: .semibold, design: .rounded))
                .monospacedDigit()
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
                Text(focusText).foregroundStyle(.secondary)
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

    private var focusText: String {
        let s = model.snapshot
        switch s.pomodoroStatus {
        case "ACTIVE": return "Focus · \(s.focusIntention) · \(s.pomodoroClock)"
        case "BREAK": return "Break · \(s.pomodoroClock)"
        default: return "Idle"
        }
    }
}
