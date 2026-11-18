import Foundation

/// What follows a block, as announced in its ending cue.
public enum NextUp: Hashable, Sendable {
    case block(areaID: AreaID, start: Date)
    case meeting
    case freeTime
    case endOfPlan
}

public enum CueKind: Hashable, Sendable {
    case blockStart
    case blockEnding(next: NextUp)
    case resume(until: Date)
    case unplanned(until: Date)
}

public struct ScheduledCue: Hashable, Sendable {
    public let at: Date
    public let kind: CueKind
    /// `nil` only for `.unplanned`.
    public let block: PlannedBlock?

    public init(at: Date, kind: CueKind, block: PlannedBlock?) {
        self.at = at
        self.kind = kind
        self.block = block
    }
}

public enum CueScheduler {
    /// The day's cues, sorted by time. Dropped blocks produce none.
    public static func schedule(
        _ fitted: [FittedBlock],
        meetings: [DateInterval],
        endingLead: TimeInterval = 120,
        gapThreshold: TimeInterval = 900,
        nextBlockHorizon: TimeInterval = 900
    ) -> [ScheduledCue] {
        let merged = PlanFitter.merge(meetings)
        let kept = fitted
            .compactMap { f in f.start.flatMap { s in f.end.map { e in (fitted: f, start: s, end: e) } } }
            .sorted { $0.start < $1.start }

        var cues: [(cue: ScheduledCue, order: Int)] = []
        for (index, item) in kept.enumerated() {
            let block = item.fitted.block
            let next = index + 1 < kept.count ? kept[index + 1] : nil

            cues.append((ScheduledCue(at: item.start, kind: .blockStart, block: block), 1))
            for part in item.fitted.parts.dropFirst() {
                cues.append((ScheduledCue(at: part.start, kind: .resume(until: item.end), block: block), 0))
            }
            let upcoming = nextUp(after: item.end, next: next.map { ($0.fitted.block.areaID, $0.start) },
                                  meetings: merged, horizon: nextBlockHorizon)
            cues.append((ScheduledCue(at: item.end - endingLead, kind: .blockEnding(next: upcoming), block: block), 0))

            if let next, let at = unplannedCue(from: item.end, to: next.start, meetings: merged, threshold: gapThreshold) {
                cues.append((ScheduledCue(at: at, kind: .unplanned(until: next.start), block: nil), 0))
            }
        }
        return cues.sorted { ($0.cue.at, $0.order) < ($1.cue.at, $1.order) }.map(\.cue)
    }

    private static func nextUp(
        after end: Date, next: (area: AreaID, start: Date)?, meetings: [DateInterval], horizon: TimeInterval
    ) -> NextUp {
        guard let next else { return .endOfPlan }
        if meetings.contains(where: { $0.start >= end && $0.start < next.start }) { return .meeting }
        if next.start.timeIntervalSince(end) <= horizon { return .block(areaID: next.area, start: next.start) }
        return .freeTime
    }

    /// The first instant after `end` outside any meeting, when the gap to `nextStart` has
    /// at least `threshold` of meeting-free time.
    private static func unplannedCue(
        from end: Date, to nextStart: Date, meetings: [DateInterval], threshold: TimeInterval
    ) -> Date? {
        let meetingTime = meetings.reduce(0.0) { total, m in
            let lower = max(m.start, end)
            let upper = min(m.end, nextStart)
            return total + max(0, upper.timeIntervalSince(lower))
        }
        guard nextStart.timeIntervalSince(end) - meetingTime >= threshold else { return nil }
        if let covering = meetings.first(where: { $0.start <= end && end < $0.end }) {
            return covering.end
        }
        return end
    }
}
