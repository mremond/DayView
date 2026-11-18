import Foundation

public struct PlanArc: Sendable, Hashable {
    public let blockID: BlockID
    public let areaID: AreaID
    public let startAngleDegrees: Double
    public let sweepDegrees: Double

    public init(blockID: BlockID, areaID: AreaID, startAngleDegrees: Double, sweepDegrees: Double) {
        self.blockID = blockID
        self.areaID = areaID
        self.startAngleDegrees = startAngleDegrees
        self.sweepDegrees = sweepDegrees
    }
}

public struct SpanArc: Sendable, Hashable {
    public let id: UUID
    public let areaID: AreaID
    public let startAngleDegrees: Double
    public let sweepDegrees: Double

    public init(id: UUID, areaID: AreaID, startAngleDegrees: Double, sweepDegrees: Double) {
        self.id = id
        self.areaID = areaID
        self.startAngleDegrees = startAngleDegrees
        self.sweepDegrees = sweepDegrees
    }
}

public enum PlanRing {
    /// The ring's day window from wall-clock minutes, matching the Kotlin `dayWindowFor` clamps:
    /// start 0...23:29, end start+30...23:59. Wall-clock based, so DST days keep their local times.
    public static func window(day: LocalDay, startMinute: Int, endMinute: Int, calendar: Calendar) -> DateInterval {
        let start = min(max(startMinute, 0), 23 * 60 + 29)
        let end = min(max(endMinute, start + 30), 23 * 60 + 59)
        return DateInterval(
            start: DayPlan.instant(minute: start, of: day, calendar: calendar),
            end: DayPlan.instant(minute: end, of: day, calendar: calendar)
        )
    }

    /// One arc per fitted part, clipped to the window. Angle is -90 at the window start, clockwise.
    public static func arcs(_ fitted: [FittedBlock], windowStart: Date, windowEnd: Date) -> [PlanArc] {
        fitted.flatMap { f in
            f.parts.compactMap { part -> PlanArc? in
                guard let a = angles(part, windowStart: windowStart, windowEnd: windowEnd) else { return nil }
                return PlanArc(
                    blockID: f.block.id, areaID: f.block.areaID,
                    startAngleDegrees: a.start, sweepDegrees: a.sweep
                )
            }
        }
    }

    /// One arc per span (a Focus session), clipped to the window, same angles as `arcs`.
    public static func spanArcs(
        _ spans: [(id: UUID, areaID: AreaID, interval: DateInterval)], windowStart: Date, windowEnd: Date
    ) -> [SpanArc] {
        spans.compactMap { span in
            guard let a = angles(span.interval, windowStart: windowStart, windowEnd: windowEnd) else { return nil }
            return SpanArc(id: span.id, areaID: span.areaID, startAngleDegrees: a.start, sweepDegrees: a.sweep)
        }
    }

    /// Index of the arc containing `angleDegrees`, or the nearest one within `tolerance` degrees.
    public static func arcIndex(at angleDegrees: Double, in arcs: [PlanArc], tolerance: Double = 4) -> Int? {
        nearest(angleDegrees, in: arcs.map { ($0.startAngleDegrees, $0.sweepDegrees) }, tolerance: tolerance)
    }

    /// Index of the span arc containing `angleDegrees`, or the nearest one within `tolerance` degrees.
    public static func spanIndex(at angleDegrees: Double, in arcs: [SpanArc], tolerance: Double = 4) -> Int? {
        nearest(angleDegrees, in: arcs.map { ($0.startAngleDegrees, $0.sweepDegrees) }, tolerance: tolerance)
    }

    private static func angles(
        _ interval: DateInterval, windowStart: Date, windowEnd: Date
    ) -> (start: Double, sweep: Double)? {
        let span = windowEnd.timeIntervalSince(windowStart)
        guard span > 0 else { return nil }
        let start = max(interval.start, windowStart)
        let end = min(interval.end, windowEnd)
        guard end > start else { return nil }
        return (-90 + 360 * start.timeIntervalSince(windowStart) / span, 360 * end.timeIntervalSince(start) / span)
    }

    private static func nearest(_ angleDegrees: Double, in arcs: [(start: Double, sweep: Double)], tolerance: Double) -> Int? {
        let probe = normalized(angleDegrees)
        var best: (index: Int, distance: Double)?
        for (index, arc) in arcs.enumerated() {
            let offset = normalized(probe - normalized(arc.start))
            let distance = offset <= arc.sweep ? 0 : min(offset - arc.sweep, 360 - offset)
            if distance < (best?.distance ?? .greatestFiniteMagnitude) { best = (index, distance) }
        }
        guard let best, best.distance <= tolerance else { return nil }
        return best.index
    }

    private static func normalized(_ value: Double) -> Double {
        let r = value.truncatingRemainder(dividingBy: 360)
        return r < 0 ? r + 360 : r
    }
}
