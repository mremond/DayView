import Foundation

/// Builds deep-focus stretches from on-goal samples taken about once a second.
/// A stretch opens on an on-goal sample and ends at its last on-goal sample. Off-goal or neutral
/// samples shorter than `bridge` do not break it, a longer silence does, and so does a gap in the
/// sampling itself (sleep, a paused app) or a clock that steps backwards. Stretches under `minimum`
/// are not kept.
public struct PresenceAccumulator: Sendable {
    public static let bridge: TimeInterval = 60
    public static let minimum: TimeInterval = 120
    public static let maxSampleGap: TimeInterval = 5

    /// The closed stretches so far, for live drawing.
    public private(set) var stretches: [DateInterval] = []
    private var openStart: Date?
    private var lastOnGoal: Date?
    private var lastSample: Date?

    public init() {}

    public mutating func sample(onGoal: Bool, at now: Date) {
        if let last = lastSample, now < last || now.timeIntervalSince(last) > Self.maxSampleGap {
            closeOpen()
        }
        lastSample = now
        if onGoal {
            if openStart == nil { openStart = now }
            lastOnGoal = now
        } else if let last = lastOnGoal, openStart != nil, now.timeIntervalSince(last) > Self.bridge {
            closeOpen()
        }
    }

    /// Closes any open stretch at its last on-goal sample and returns every stretch of at least `minimum`.
    /// `now` is not used: stretches end at their last on-goal sample, never at the time of the call.
    /// It is kept so callers read the same way as the other time-based APIs.
    public mutating func finish(at now: Date) -> [DateInterval] {
        closeOpen()
        return stretches.sorted { $0.start < $1.start }
    }

    /// The closed stretches plus the open one up to its last on-goal sample, whatever its length.
    /// `now` is not used, for the same reason as in `finish(at:)`.
    public func live(at now: Date) -> [DateInterval] {
        guard let start = openStart, let end = lastOnGoal, end >= start else { return stretches }
        return stretches + [DateInterval(start: start, end: end)]
    }

    /// Total deep time so far: the closed stretches plus the open one once it reaches `minimum`,
    /// so the running total never counts time that `finish(at:)` would drop.
    public func liveTotal(at now: Date) -> TimeInterval {
        live(at: now).filter { $0.duration >= Self.minimum }.reduce(0) { $0 + $1.duration }
    }

    private mutating func closeOpen() {
        if let start = openStart, let end = lastOnGoal, end >= start, end.timeIntervalSince(start) >= Self.minimum {
            stretches.append(DateInterval(start: start, end: end))
        }
        openStart = nil
    }
}
