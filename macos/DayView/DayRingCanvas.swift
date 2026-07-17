import SwiftUI
import DayViewKit

/// The DayView dial, shared by the main and mini windows. Draws (in order) the track, an
/// optional goal halo, 24 hour ticks, the ratio-accented remaining sweep (a rotated
/// gradient once the day has started, else a uniform ring), the moment marker, the
/// finished rest state, the calendar-busy lane (inner, glow + core), and the detour lane
/// (outer, glow + core). Constants mirror the JVM CountdownCircle. Drawing only — the
/// interior text and hover live with the callers.
struct DayRingCanvas: View {
    let momentAngleDegrees: Double
    let remainingRatio: Double
    let isFinished: Bool
    let hasStarted: Bool
    var hasGoal: Bool = false
    var busyArcs: [BusyArcSnapshot] = []
    var detourBodies: [DetourBodySnapshot] = []
    var lineWidth: CGFloat = DayRingCanvas.defaultLineWidth
    var inset: CGFloat = DayRingCanvas.defaultInset

    @Environment(\.colorScheme) private var colorScheme

    // Shared geometry, referenced by RingView's hover hit-testing so the drawn lane and the
    // hit band cannot drift.
    static let defaultInset: CGFloat = 40
    static let defaultLineWidth: CGFloat = 18
    static let busyRadiusFactor: CGFloat = 0.95
    static let busyWidthFactor: CGFloat = 0.7
    static let detourRadiusFactor: CGFloat = 0.95

    var body: some View {
        Canvas { context, size in
            let palette = DayViewPalette.current(for: colorScheme)
            let side = min(size.width, size.height) - inset * 2
            let radius = max(side / 2, 1)
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let accent = remainingRatio < 0.2 ? palette.amber : palette.mint

            // 1. Track.
            stroke(&context, circleAt: center, radius: radius, color: palette.overlay.opacity(0.075))

            // 2. Goal halo.
            if hasGoal {
                let haloRadius = min(size.width, size.height) * 0.30
                var halo = Path()
                halo.addArc(center: center, radius: haloRadius, startAngle: .degrees(0), endAngle: .degrees(360), clockwise: false)
                context.fill(
                    halo,
                    with: .radialGradient(
                        Gradient(colors: [palette.amber.opacity(0.10), .clear]),
                        center: center, startRadius: 0, endRadius: haloRadius
                    )
                )
            }

            // 3. Hour ticks (24, majors every 90°).
            let outer = min(size.width, size.height) / 2 - 1
            for i in 0..<24 {
                let major = i % 6 == 0
                let angle = Double(i) * 15.0 - 90.0
                let inner = outer - (major ? 10 : 5)
                let a = angle * .pi / 180.0
                var tick = Path()
                tick.move(to: CGPoint(x: center.x + cos(a) * inner, y: center.y + sin(a) * inner))
                tick.addLine(to: CGPoint(x: center.x + cos(a) * outer, y: center.y + sin(a) * outer))
                context.stroke(tick, with: .color(palette.overlay.opacity(major ? 0.28 : 0.12)), lineWidth: 1)
            }

            if !isFinished && remainingRatio > 0 {
                // 4. Remaining sweep.
                let sweepDegrees = remainingRatio * 360.0
                if hasStarted {
                    // Rotate the drawing so the gradient seam falls at the arc's start (the
                    // gap in the ring) rather than mid-arc.
                    context.drawLayer { layer in
                        layer.translateBy(x: center.x, y: center.y)
                        layer.rotate(by: .degrees(momentAngleDegrees))
                        layer.translateBy(x: -center.x, y: -center.y)
                        var sweep = Path()
                        sweep.addArc(center: center, radius: radius, startAngle: .degrees(0), endAngle: .degrees(sweepDegrees), clockwise: false)
                        layer.stroke(
                            sweep,
                            with: .conicGradient(
                                Gradient(colors: [accent, accent.opacity(0.62)]),
                                center: center, angle: .degrees(0)
                            ),
                            style: StrokeStyle(lineWidth: lineWidth, lineCap: .round)
                        )
                    }
                    // 5. Moment marker.
                    let a = momentAngleDegrees * .pi / 180.0
                    let markerCenter = CGPoint(x: center.x + cos(a) * radius, y: center.y + sin(a) * radius)
                    fillCircle(&context, at: markerCenter, radius: lineWidth * 0.68, color: palette.amber.opacity(0.2))
                    fillCircle(&context, at: markerCenter, radius: lineWidth * 0.4, color: palette.amber)
                    let hi = CGPoint(x: markerCenter.x - lineWidth * 0.1, y: markerCenter.y - lineWidth * 0.1)
                    fillCircle(&context, at: hi, radius: lineWidth * 0.1, color: .white.opacity(0.45))
                } else {
                    // Before the day starts: uniform full ring (no gradient seam).
                    var full = Path()
                    full.addArc(center: center, radius: radius, startAngle: .degrees(-90), endAngle: .degrees(-90 + sweepDegrees), clockwise: false)
                    context.stroke(full, with: .color(accent), style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                }
            } else if isFinished {
                // 6. Finished rest state: calm neutral ring + resting marker at 12 o'clock.
                stroke(&context, circleAt: center, radius: radius, color: palette.overlay.opacity(0.16))
                let restCenter = CGPoint(x: center.x, y: center.y - radius)
                fillCircle(&context, at: restCenter, radius: lineWidth * 0.6, color: palette.overlay.opacity(0.12))
                fillCircle(&context, at: restCenter, radius: lineWidth * 0.34, color: palette.muted)
            }

            // 7. Busy lane (glow + core).
            let busyRadius = radius - lineWidth * Self.busyRadiusFactor
            for arc in busyArcs {
                let color = palette.busyColor(Int(arc.colorIndex))
                var lane = Path()
                lane.addArc(center: center, radius: busyRadius, startAngle: .degrees(arc.startAngleDegrees), endAngle: .degrees(arc.startAngleDegrees + arc.sweepDegrees), clockwise: false)
                context.stroke(lane, with: .color(color.opacity(0.16)), style: StrokeStyle(lineWidth: lineWidth * 0.7, lineCap: .round))
                context.stroke(lane, with: .color(color.opacity(0.92)), style: StrokeStyle(lineWidth: lineWidth * 0.42, lineCap: .round))
            }

            // 8. Detour lane (outer, glow + core).
            let detourRadius = radius + lineWidth * Self.detourRadiusFactor
            for body in detourBodies {
                let color = palette.detourColor(Int(body.colorIndex))
                var lane = Path()
                lane.addArc(center: center, radius: detourRadius, startAngle: .degrees(body.startAngleDegrees), endAngle: .degrees(body.startAngleDegrees + body.sweepDegrees), clockwise: false)
                context.stroke(lane, with: .color(color.opacity(0.16)), style: StrokeStyle(lineWidth: lineWidth * 0.7, lineCap: .round))
                context.stroke(lane, with: .color(color.opacity(0.92)), style: StrokeStyle(lineWidth: lineWidth * 0.42, lineCap: .round))
            }
        }
    }

    private func stroke(_ context: inout GraphicsContext, circleAt center: CGPoint, radius: CGFloat, color: Color) {
        var path = Path()
        path.addArc(center: center, radius: radius, startAngle: .degrees(0), endAngle: .degrees(360), clockwise: false)
        context.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
    }

    private func fillCircle(_ context: inout GraphicsContext, at center: CGPoint, radius: CGFloat, color: Color) {
        let rect = CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2)
        context.fill(Path(ellipseIn: rect), with: .color(color))
    }
}
