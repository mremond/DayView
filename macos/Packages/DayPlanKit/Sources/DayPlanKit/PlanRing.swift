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
        let span = windowEnd.timeIntervalSince(windowStart)
        guard span > 0 else { return [] }
        return fitted.flatMap { f in
            f.parts.compactMap { part -> PlanArc? in
                let start = max(part.start, windowStart)
                let end = min(part.end, windowEnd)
                guard end > start else { return nil }
                return PlanArc(
                    blockID: f.block.id, areaID: f.block.areaID,
                    startAngleDegrees: -90 + 360 * start.timeIntervalSince(windowStart) / span,
                    sweepDegrees: 360 * end.timeIntervalSince(start) / span
                )
            }
        }
    }

    /// Index of the arc containing `angleDegrees`, or the nearest one within `tolerance` degrees.
    public static func arcIndex(at angleDegrees: Double, in arcs: [PlanArc], tolerance: Double = 4) -> Int? {
        let probe = normalized(angleDegrees)
        var best: (index: Int, distance: Double)?
        for (index, arc) in arcs.enumerated() {
            let offset = normalized(probe - normalized(arc.startAngleDegrees))
            let distance = offset <= arc.sweepDegrees ? 0 : min(offset - arc.sweepDegrees, 360 - offset)
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
