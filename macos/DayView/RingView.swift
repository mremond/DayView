import SwiftUI
import DayViewKit

struct RingView: View {
    // Hardcoded working window for the walking skeleton: 09:00–18:00.
    private static let startMinutes: Int32 = 540
    private static let endMinutes: Int32 = 1080

    @State private var snapshot = RingView.currentSnapshot()
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

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
                    endAngle: .degrees(snapshot.momentAngleDegrees),
                    clockwise: false
                )
                context.stroke(
                    sweep,
                    with: .color(.accentColor),
                    style: StrokeStyle(lineWidth: lineWidth, lineCap: .round)
                )
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            Text(timeText)
                .font(.system(size: 44, weight: .semibold, design: .rounded))
                .monospacedDigit()
        }
        .padding(32)
        .onReceive(timer) { _ in
            snapshot = RingView.currentSnapshot()
        }
    }

    private var timeText: String {
        if snapshot.isFinished { return "Day over" }
        return String(
            format: "%dh %02dm",
            Int(snapshot.remainingHours),
            Int(snapshot.remainingMinutes)
        )
    }

    private static func currentSnapshot() -> DayProgressSnapshot {
        DayViewCore.shared.dayProgress(
            nowEpochMillis: Int64(Date().timeIntervalSince1970 * 1000),
            startMinutes: startMinutes,
            endMinutes: endMinutes
        )
    }
}
