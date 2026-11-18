import Foundation

/// Builds deep-focus stretches from on-goal samples taken about once a second.
/// A stretch opens on an on-goal sample and ends at its last on-goal sample. Off-goal or neutral
/// samples shorter than `bridge` do not break it, a longer silence does, and so does a gap in the
/// sampling itself (sleep, a paused app). Stretches under `minimum` are not kept.
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
        if let last = lastSample, now.timeIntervalSince(last) > Self.maxSampleGap {
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
    public mutating func finish(at now: Date) -> [DateInterval] {
        closeOpen()
        return stretches.sorted { $0.start < $1.start }
    }

    /// The closed stretches plus the open one up to its last on-goal sample, whatever its length.
    public func live(at now: Date) -> [DateInterval] {
        guard let start = openStart, let end = lastOnGoal else { return stretches }
        return stretches + [DateInterval(start: start, end: end)]
    }

    private mutating func closeOpen() {
        if let start = openStart, let end = lastOnGoal, end.timeIntervalSince(start) >= Self.minimum {
            stretches.append(DateInterval(start: start, end: end))
        }
        openStart = nil
    }
}
