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

public enum ClosureOutcome: String, Codable, Sendable {
    case completed, progressed, toResume
}

/// Runs the Focus: a planned block's Focus starts by itself, a manual Focus can start anytime,
/// and when a block starts during a manual Focus the plan wins.
public struct FocusEngine: Sendable {
    public static let manualMinutes = 5...180

    public private(set) var current: FocusSession?
    /// Closing ritual queue: every ended or taken-over Focus, until closed.
    public private(set) var pendingClosures: [FocusRecord] = []
    /// Set when a manual Focus completes; cleared when any Focus starts.
    public private(set) var breakStartedAt: Date?

    /// Blocks whose Focus has started once; they never start again.
    private var startedBlockIDs: Set<BlockID> = []
    /// The parts of the current planned session's block, as last seen in the plan.
    private var currentParts: [DateInterval] = []

    public init() {}

    public mutating func advance(to now: Date, plan: [FittedBlock]) -> [FocusEvent] {
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

    public mutating func close(recordID: UUID) -> FocusRecord? {
        guard let index = pendingClosures.firstIndex(where: { $0.session.id == recordID }) else { return nil }
        return pendingClosures.remove(at: index)
    }

    // MARK: - State transitions

    /// Plan wins: a block that started while the manual Focus was still on (its end included)
    /// interrupts it at the block's start, even when the manual Focus is already due.
    private mutating func takeOverManual(at now: Date, plan: [FittedBlock]) -> FocusEvent? {
        guard let manual = current, manual.origin == .manual,
              let running = nextBlockToStart(at: now, plan: plan),
              let blockStart = running.start, blockStart <= manual.end
        else { return nil }
        let actualEnd = min(max(blockStart, manual.start), manual.end)
        let interrupted = finish(at: actualEnd, reason: .tookOver)
        return .tookOver(interrupted: interrupted, by: startPlanned(running, now: now))
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
