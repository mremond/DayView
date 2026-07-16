import SwiftUI
import DayViewKit

/// The countdown ring shared by the main window and the mini window: a gray full-circle
/// track, an optional calendar-busy lane (one thin colored arc per merged busy block,
/// concentric inside the main lane — JVM geometry), and the accent remaining-time sweep
/// anchored at 12 o'clock. Drawing only — text, hover, and layout stay with the callers.
struct DayRingCanvas: View {
    let momentAngleDegrees: Double
    var lineWidth: CGFloat = 18
    var inset: CGFloat = 40
    var busyArcs: [BusyArcSnapshot] = []

    @Environment(\.colorScheme) private var colorScheme

    // Busy-lane geometry, shared with RingView's hover hit-testing so the hit band can
    // never drift from the drawn lane (mirrors DayViewTodayScreen.kt:1356).
    static let busyRadiusFactor: CGFloat = 0.95 // lane radius = main radius - lineWidth * this
    static let busyWidthFactor: CGFloat = 0.7 // lane stroke = lineWidth * this

    var body: some View {
        Canvas { context, size in
            let side = min(size.width, size.height) - inset * 2
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let radius = max(side / 2, 1)
            var track = Path()
            track.addArc(center: center, radius: radius, startAngle: .degrees(0), endAngle: .degrees(360), clockwise: false)
            context.stroke(track, with: .color(.gray.opacity(0.2)), lineWidth: lineWidth)
            // Busy lane: after the track, before the remaining sweep (JVM draw order).
            let busyRadius = radius - lineWidth * Self.busyRadiusFactor
            let busyWidth = lineWidth * Self.busyWidthFactor
            for arc in busyArcs {
                var lane = Path()
                lane.addArc(
                    center: center,
                    radius: busyRadius,
                    startAngle: .degrees(arc.startAngleDegrees),
                    endAngle: .degrees(arc.startAngleDegrees + arc.sweepDegrees),
                    clockwise: false
                )
                context.stroke(
                    lane,
                    with: .color(BusyPalette.color(for: Int(arc.colorIndex), scheme: colorScheme)),
                    style: StrokeStyle(lineWidth: busyWidth, lineCap: .round)
                )
            }
            var sweep = Path()
            sweep.addArc(center: center, radius: radius, startAngle: .degrees(-90), endAngle: .degrees(momentAngleDegrees), clockwise: false)
            context.stroke(sweep, with: .color(.accentColor), style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
        }
    }
}
