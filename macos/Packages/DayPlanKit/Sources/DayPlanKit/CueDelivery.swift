import Foundation

/// Decides which scheduled cues to speak right now. Called about once a second; it never
/// bursts stale cues after sleep and never repeats a cue.
public struct CueDelivery: Sendable {
    public let maxLateness: TimeInterval
    private var lastObserved: Date?
    private var delivered: Set<ScheduledCue> = []

    public init(maxLateness: TimeInterval = 90) {
        self.maxLateness = maxLateness
    }

    public mutating func due(_ schedule: [ScheduledCue], fitted: [FittedBlock], now: Date) -> [ScheduledCue] {
        if let lastObserved, now <= lastObserved { return [] }
        defer { lastObserved = now }

        let candidates = schedule.filter { cue in
            cue.at <= now && lastObserved.map { $0 < cue.at } ?? true
        }
        var result = candidates.filter { now.timeIntervalSince($0.at) <= maxLateness && !delivered.contains($0) }

        // Wake rule: a block is running but its start/resume was missed as too late.
        if let wake = wakeCue(candidates, fitted: fitted, now: now, delivering: result) {
            result.append(wake)
        }
        delivered.formUnion(result)
        return result
    }

    private func wakeCue(
        _ candidates: [ScheduledCue], fitted: [FittedBlock], now: Date, delivering: [ScheduledCue]
    ) -> ScheduledCue? {
        guard let running = fitted.first(where: { $0.isRunning(at: now) && !$0.isPaused(at: now) }) else { return nil }
        let id = running.block.id
        guard !delivering.contains(where: { $0.block?.id == id }) else { return nil }
        let latest = candidates.last { cue in
            guard cue.block?.id == id else { return false }
            switch cue.kind {
            case .blockStart, .resume: return true
            default: return false
            }
        }
        guard let latest, !delivered.contains(latest) else { return nil }
        return latest
    }
}

extension CueDelivery {
    /// Drops the start and resume cues of blocks `engine` had already started. Used once, on the
    /// first delivery after launch, with the engine as loaded (after `beginDay`, before `advance`):
    /// the wake rule then only announces blocks that started while DayView was not observing,
    /// not a block it already announced, or one the user stopped, before quitting.
    public static func dropAlreadyStarted(_ due: [ScheduledCue], startedBy engine: FocusEngine) -> [ScheduledCue] {
        due.filter { cue in
            guard let id = cue.block?.id else { return true }
            switch cue.kind {
            case .blockStart, .resume: return !engine.hasStarted(id)
            default: return true
            }
        }
    }
}

/// Announces a takeover the cue schedule cannot see.
public enum TakeoverCues {
    /// Adds a block-start cue at `now` for each takeover whose block has no block-start cue in
    /// `due`. A block that appears mid-Focus (plan edited, a meeting cancelled) takes over at
    /// once, but its scheduled start cue is already in the past and never delivered; the
    /// takeover event happens once, so this announces it exactly once.
    public static func supplement(
        due: [ScheduledCue], events: [FocusEvent], fitted: [FittedBlock], now: Date
    ) -> [ScheduledCue] {
        var result = due
        for case .tookOver(_, let session) in events {
            guard case .planned(let id) = session.origin,
                  !result.contains(where: { $0.kind == .blockStart && $0.block?.id == id }),
                  let block = fitted.first(where: { $0.block.id == id })?.block
            else { continue }
            result.append(ScheduledCue(at: now, kind: .blockStart, block: block))
        }
        return result
    }
}
