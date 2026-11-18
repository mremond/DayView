import Foundation

public enum FocusOrigin: Hashable, Codable, Sendable {
    case planned(BlockID)
    case manual
}

public struct FocusSession: Hashable, Codable, Sendable {
    public let id: UUID
    public let areaID: AreaID
    public let intention: String
    public let origin: FocusOrigin
    public let start: Date
    public var end: Date

    public init(id: UUID = UUID(), areaID: AreaID, intention: String, origin: FocusOrigin, start: Date, end: Date) {
        self.id = id
        self.areaID = areaID
        self.intention = intention
        self.origin = origin
        self.start = start
        self.end = end
    }
}

public enum FocusEndReason: Hashable, Codable, Sendable {
    case completed, stopped, tookOver
}

/// A finished Focus. Its identity is `session.id`.
public struct FocusRecord: Hashable, Codable, Sendable {
    public let session: FocusSession
    public let actualEnd: Date
    /// Time focused; for a planned session, time outside the block's parts (paused) is excluded.
    public let focused: TimeInterval
    public let reason: FocusEndReason

    public init(session: FocusSession, actualEnd: Date, focused: TimeInterval, reason: FocusEndReason) {
        self.session = session
        self.actualEnd = actualEnd
        self.focused = focused
        self.reason = reason
    }
}

public enum FocusEvent: Hashable, Sendable {
    case started(FocusSession)
    case tookOver(interrupted: FocusRecord, by: FocusSession)
    case ended(FocusRecord)
    case reopened(FocusSession, discardedRecordID: UUID)
}

/// What "Keep going" would do right now.
///
/// Caller contract, with `extension = 900` (15 min):
/// - `.extendBlock(id)`: apply `plan.keepingGoing(blockID: id, by: extension, calendar:)` and refit;
///   the running session follows its block's new end.
/// - `.reopenInterrupted(_, blockID)`: apply `plan.shiftingFrom(blockID:, by: extension, calendar:)`,
///   refit, then call `FocusEngine.reopenInterrupted(until:)` with the shifted block's FITTED start
///   after refitting (not its planned start). If `shiftingFrom` dropped the block (it moved past
///   midnight), the caller must not call `reopenInterrupted`.
public enum KeepGoingOffer: Hashable, Sendable {
    case extendBlock(BlockID)
    case reopenInterrupted(FocusRecord, blockID: BlockID)
}

public enum ClosureOutcome: String, Codable, Sendable {
    case completed, progressed, toResume
}

/// Runs the Focus: a planned block's Focus starts by itself, a manual Focus can start anytime,
/// and when a block starts during a manual Focus the plan wins.
public struct FocusEngine: Codable, Sendable, Equatable {
    public static let manualMinutes = 5...180
    /// The single source of the Keep going extension: 15 minutes.
    public static let keepGoingExtension: TimeInterval = 900

    public private(set) var current: FocusSession?
    /// Closing ritual queue: every ended or taken-over Focus, until closed.
    public private(set) var pendingClosures: [FocusRecord] = []
    /// Set when a manual Focus completes; cleared when any Focus starts.
    public private(set) var breakStartedAt: Date?

    /// Blocks whose Focus has started once; they never start again.
    private var startedBlockIDs: Set<BlockID> = []
    /// The parts of the current planned session's block, as last seen in the plan.
    private var currentParts: [DateInterval] = []
    /// The manual Focus the plan just took over, and the block that took over; cleared by any later transition.
    private var lastTakeover: Takeover?
    /// The `now` of the previous `advance`; a takeover cannot reach back before it.
    private var lastAdvance: Date?
    /// The day the engine is running; see `beginDay`.
    private var day: LocalDay?

    private struct Takeover: Codable, Sendable, Equatable {
        let interrupted: FocusRecord
        let blockID: BlockID
    }

    public init() {}

    /// Scopes the engine to `day`. A new day first ends a planned session still running from the
    /// previous day, at the earlier of its end and midnight (its record goes to `pendingClosures`),
    /// then clears started blocks and the last takeover, because planned block IDs repeat from day
    /// to day, and the break, which belongs to the previous day. A manual session and pending
    /// closures are kept.
    @discardableResult
    public mutating func beginDay(_ day: LocalDay, calendar: Calendar) -> [FocusEvent] {
        var events: [FocusEvent] = []
        if let stored = self.day, stored != day {
            if let session = current, case .planned = session.origin {
                let dayStart = day.start(in: calendar)
                let completed = session.end <= dayStart
                events.append(.ended(finish(at: min(session.end, dayStart), reason: completed ? .completed : .stopped)))
            }
            startedBlockIDs = []
            lastTakeover = nil
            breakStartedAt = nil
        }
        self.day = day
        return events
    }

    /// A copy without the advance clock, to detect real state changes: `lastAdvance` moves every tick.
    public func withoutClock() -> FocusEngine {
        var copy = self
        copy.lastAdvance = nil
        return copy
    }

    public mutating func advance(to now: Date, plan: [FittedBlock]) -> [FocusEvent] {
        defer { lastAdvance = now }
        if let takeover = takeOverManual(at: now, plan: plan) { return [takeover] }
        var events: [FocusEvent] = []
        if let ended = endCurrentIfDue(now: now, plan: plan) {
            events.append(.ended(ended))
        }
        if current == nil, let running = nextBlockToStart(at: now, plan: plan) {
            events.append(.started(startPlanned(running, now: now)))
        }
        return events
    }

    /// Whether the Focus of block `id` has started today (it never starts again).
    public func hasStarted(_ id: BlockID) -> Bool {
        startedBlockIDs.contains(id)
    }

    /// Time focused so far in the running session, as its record would count it: for a planned
    /// session, time outside the block's parts (paused for a meeting) is excluded. 0 when idle.
    public func focusedSoFar(at now: Date) -> TimeInterval {
        guard let current else { return 0 }
        return focusedTime(of: current, until: min(now, current.end))
    }

    public mutating func startManual(areaID: AreaID, intention: String, minutes: Int, now: Date) -> [FocusEvent] {
        var events: [FocusEvent] = []
        if current != nil {
            events.append(.ended(finish(at: now, reason: .stopped)))
        }
        let clamped = min(max(minutes, Self.manualMinutes.lowerBound), Self.manualMinutes.upperBound)
        let session = FocusSession(
            areaID: areaID, intention: intention, origin: .manual,
            start: now, end: now.addingTimeInterval(TimeInterval(clamped * 60))
        )
        begin(session)
        events.append(.started(session))
        return events
    }

    public mutating func stop(now: Date, plan: [FittedBlock]) -> [FocusEvent] {
        guard let current else { return [] }
        if case let .planned(id) = current.origin, let fitted = kept(id, in: plan) {
            refresh(with: fitted)
        }
        return [.ended(finish(at: now, reason: .stopped))]
    }

    /// Pure query. Reopening a just-taken-over manual Focus wins over extending the block.
    /// `.extendBlock` is withheld when a meeting starts within 15 minutes after the fitted end:
    /// extending would be cut back by the refit while the rest of the day shifted.
    public func keepGoingOffer(
        now: Date, plan: [FittedBlock], meetings: [DateInterval], window: TimeInterval = 120
    ) -> KeepGoingOffer? {
        guard let current, case let .planned(id) = current.origin else { return nil }
        if let takeover = lastTakeover, takeover.blockID == id {
            let since = now.timeIntervalSince(takeover.interrupted.actualEnd)
            if since >= 0, since <= window { return .reopenInterrupted(takeover.interrupted, blockID: id) }
        }
        guard let end = kept(id, in: plan)?.end, now < end, end.timeIntervalSince(now) <= window else { return nil }
        let extensionReach = end.addingTimeInterval(Self.keepGoingExtension)
        if PlanFitter.merge(meetings).contains(where: { $0.start >= end && $0.start < extensionReach }) { return nil }
        return .extendBlock(id)
    }

    /// Gives back the manual Focus the plan just took over, until `until`: the planned session is
    /// discarded without a record and its block may start again; the interrupted record leaves the queue.
    public mutating func reopenInterrupted(until: Date) -> [FocusEvent] {
        guard let takeover = lastTakeover, let current, current.origin == .planned(takeover.blockID) else { return [] }
        let record = takeover.interrupted
        startedBlockIDs.remove(takeover.blockID)
        pendingClosures.removeAll { $0.session.id == record.session.id }
        var session = record.session
        session.end = until
        currentParts = []
        begin(session)
        return [.reopened(session, discardedRecordID: record.session.id)]
    }

    public mutating func close(recordID: UUID) -> FocusRecord? {
        guard let index = pendingClosures.firstIndex(where: { $0.session.id == recordID }) else { return nil }
        return pendingClosures.remove(at: index)
    }

    // MARK: - State transitions

    /// Plan wins: a block that started while the manual Focus was still on (its end included)
    /// interrupts it at the block's start, even when the manual Focus is already due. A block that
    /// appears later (plan edited mid-Focus) interrupts it at the previous advance, not retroactively.
    private mutating func takeOverManual(at now: Date, plan: [FittedBlock]) -> FocusEvent? {
        guard let manual = current, manual.origin == .manual,
              let running = nextBlockToStart(at: now, plan: plan),
              let blockStart = running.start, blockStart <= manual.end
        else { return nil }
        let actualEnd = min(max(blockStart, lastAdvance ?? blockStart, manual.start), manual.end)
        let interrupted = finish(at: actualEnd, reason: .tookOver)
        let planned = startPlanned(running, now: now)
        lastTakeover = Takeover(interrupted: interrupted, blockID: running.block.id)
        return .tookOver(interrupted: interrupted, by: planned)
    }

    /// Ends the current session when its time is up, or when its block left the plan.
    private mutating func endCurrentIfDue(now: Date, plan: [FittedBlock]) -> FocusRecord? {
        guard let current else { return nil }
        switch current.origin {
        case .manual:
            guard now >= current.end else { return nil }
            breakStartedAt = current.end
            return finish(at: current.end, reason: .completed)
        case let .planned(id):
            guard let fitted = kept(id, in: plan) else { return finish(at: now, reason: .stopped) }
            refresh(with: fitted)
            let end = self.current!.end
            guard now >= end else { return nil }
            return finish(at: end, reason: .completed)
        }
    }

    private mutating func startPlanned(_ fitted: FittedBlock, now: Date) -> FocusSession {
        let block = fitted.block
        let session = FocusSession(
            areaID: block.areaID, intention: block.intention, origin: .planned(block.id),
            start: now, end: fitted.end!
        )
        startedBlockIDs.insert(block.id)
        currentParts = fitted.parts
        begin(session)
        return session
    }

    private mutating func begin(_ session: FocusSession) {
        current = session
        breakStartedAt = nil
        lastTakeover = nil
    }

    /// Ends the current session at `actualEnd` and queues its record for closing.
    private mutating func finish(at actualEnd: Date, reason: FocusEndReason) -> FocusRecord {
        let session = current!
        let record = FocusRecord(
            session: session, actualEnd: actualEnd,
            focused: focusedTime(of: session, until: actualEnd), reason: reason
        )
        current = nil
        currentParts = []
        lastTakeover = nil
        pendingClosures.append(record)
        return record
    }

    private mutating func refresh(with fitted: FittedBlock) {
        current?.end = fitted.end!
        currentParts = fitted.parts
    }

    private func focusedTime(of session: FocusSession, until actualEnd: Date) -> TimeInterval {
        guard case .planned = session.origin else { return max(0, actualEnd.timeIntervalSince(session.start)) }
        return currentParts.reduce(0) { total, part in
            total + max(0, min(part.end, actualEnd).timeIntervalSince(max(part.start, session.start)))
        }
    }

    private func nextBlockToStart(at now: Date, plan: [FittedBlock]) -> FittedBlock? {
        plan.first { !$0.isDropped && $0.isRunning(at: now) && !startedBlockIDs.contains($0.block.id) }
    }

    private func kept(_ id: BlockID, in plan: [FittedBlock]) -> FittedBlock? {
        plan.first { $0.block.id == id && !$0.isDropped }
    }
}
