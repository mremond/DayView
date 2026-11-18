import Foundation

public enum EngagedTime {
    /// The focused time minus the part of `window` covered by the union of `detours`, never below 0.
    public static func compute(focused: TimeInterval, window: DateInterval, detours: [DateInterval]) -> TimeInterval {
        let away = PlanFitter.merge(detours).reduce(0.0) { total, detour in
            guard let overlap = detour.intersection(with: window) else { return total }
            return total + overlap.duration
        }
        return max(0, focused - away)
    }
}
