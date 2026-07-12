import SwiftUI
import DayViewKit

struct RingView: View {
    @StateObject private var model = TodayModel()

    var body: some View {
        VStack(spacing: 24) {
            Canvas { context, size in
                let inset: CGFloat = 40
                let side = min(size.width, size.height) - inset * 2
                let center = CGPoint(x: size.width / 2, y: size.height / 2)
                let radius = side / 2
                let lineWidth: CGFloat = 18

                var track = Path()
                track.addArc(
                    center: center, radius: radius,
                    startAngle: .degrees(0), endAngle: .degrees(360),
                    clockwise: false
                )
                context.stroke(track, with: .color(.gray.opacity(0.2)), lineWidth: lineWidth)

                var sweep = Path()
                sweep.addArc(
                    center: center, radius: radius,
                    startAngle: .degrees(-90),
                    endAngle: .degrees(model.snapshot.momentAngleDegrees),
                    clockwise: false
                )
                context.stroke(
                    sweep,
                    with: .color(.accentColor),
                    style: StrokeStyle(lineWidth: lineWidth, lineCap: .round)
                )
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            Text(model.snapshot.dayStatus)
                .font(.system(size: 44, weight: .semibold, design: .rounded))
                .monospacedDigit()

            Text(focusText)
                .foregroundStyle(.secondary)

            HStack(spacing: 16) {
                Button("Start focus") { model.startFocus() }
                Button("Stop focus") { model.stopFocus() }
            }
        }
        .padding(32)
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
