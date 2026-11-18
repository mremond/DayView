import Foundation

public enum FitNote: Hashable, Sendable {
    /// A meeting sits inside the block, with kept time on both sides.
    case splitAround(DateInterval)
    /// A meeting cut the block's start or end, or a too-short piece was discarded.
    case trimmed
    /// No usable time remains.
    case dropped
}

/// A planned block cut around meetings: the focus time that actually remains.
public struct FittedBlock: Hashable, Sendable {
    public let block: PlannedBlock
    public let parts: [DateInterval]
    public let notes: [FitNote]

    public init(block: PlannedBlock, parts: [DateInterval], notes: [FitNote]) {
        self.block = block
        self.parts = parts
        self.notes = notes
    }

    public var isDropped: Bool { parts.isEmpty }
    public var start: Date? { parts.first?.start }
    public var end: Date? { parts.last?.end }

    /// Between the first part's start and the last part's end (inclusive start, exclusive end).
    public func isRunning(at now: Date) -> Bool {
        guard let start, let end else { return false }
        return start <= now && now < end
    }

    /// Running, but inside a meeting gap between two parts.
    public func isPaused(at now: Date) -> Bool {
        isRunning(at: now) && !parts.contains { $0.start <= now && now < $0.end }
    }
}

public enum PlanFitter {
    public static func fit(
        _ plan: DayPlan, meetings: [DateInterval], minimumPart: TimeInterval = 600
    ) -> [FittedBlock] {
        let merged = merge(meetings)
        return plan.blocks.map { fit($0, around: merged, minimumPart: minimumPart) }
    }

    private static func fit(_ block: PlannedBlock, around merged: [DateInterval], minimumPart: TimeInterval) -> FittedBlock {
        let overlapping = merged.filter { $0.start < block.end && $0.end > block.start }
        let pieces = subtract(overlapping, from: block)
        let parts = pieces.filter { $0.duration >= minimumPart }
        if parts.isEmpty {
            return FittedBlock(block: block, parts: [], notes: [.dropped])
        }

        var notes: [FitNote] = overlapping
            .filter { m in
                m.start > block.start && m.end < block.end
                    && parts.contains { $0.end == m.start } && parts.contains { $0.start == m.end }
            }
            .map { .splitAround($0) }
        let cutEdge = overlapping.contains { $0.start <= block.start || $0.end >= block.end }
        if cutEdge || parts.count != pieces.count {
            notes.append(.trimmed)
        }
        return FittedBlock(block: block, parts: parts, notes: notes)
    }

    /// Sorts and merges meetings that overlap or touch.
    private static func merge(_ meetings: [DateInterval]) -> [DateInterval] {
        var result: [DateInterval] = []
        for m in meetings.sorted(by: { $0.start < $1.start }) {
            if let last = result.last, m.start <= last.end {
                result[result.count - 1] = DateInterval(start: last.start, end: max(last.end, m.end))
            } else {
                result.append(m)
            }
        }
        return result
    }

    /// The block's non-empty gaps between the (sorted, merged) meetings.
    private static func subtract(_ meetings: [DateInterval], from block: PlannedBlock) -> [DateInterval] {
        var pieces: [DateInterval] = []
        var cursor = block.start
        for m in meetings {
            if m.start > cursor { pieces.append(DateInterval(start: cursor, end: m.start)) }
            cursor = max(cursor, m.end)
        }
        if cursor < block.end { pieces.append(DateInterval(start: cursor, end: block.end)) }
        return pieces
    }
}
