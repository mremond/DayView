import SwiftUI

/// The countdown ring shared by the main window and the mini window: a gray full-circle
/// track plus the accent remaining-time sweep anchored at 12 o'clock. Drawing only —
/// the countdown text and layout stay with the callers.
struct DayRingCanvas: View {
    let momentAngleDegrees: Double
    var lineWidth: CGFloat = 18
    var inset: CGFloat = 40

    var body: some View {
        Canvas { context, size in
            let side = min(size.width, size.height) - inset * 2
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let radius = max(side / 2, 1)
            var track = Path()
            track.addArc(center: center, radius: radius, startAngle: .degrees(0), endAngle: .degrees(360), clockwise: false)
            context.stroke(track, with: .color(.gray.opacity(0.2)), lineWidth: lineWidth)
            var sweep = Path()
            sweep.addArc(center: center, radius: radius, startAngle: .degrees(-90), endAngle: .degrees(momentAngleDegrees), clockwise: false)
            context.stroke(sweep, with: .color(.accentColor), style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
        }
    }
}
