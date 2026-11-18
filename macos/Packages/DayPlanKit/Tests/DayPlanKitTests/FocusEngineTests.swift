import Foundation
import Testing
@testable import DayPlanKit

private func block(_ start: String, _ end: String, area: AreaID = AreaID(), intention: String = "Focus") -> PlannedBlock {
    PlannedBlock(id: BlockID(), start: at(start), end: at(end), areaID: area, intention: intention)
}

private func fitted(_ blocks: [PlannedBlock], meetings: [DateInterval] = []) -> [FittedBlock] {
    PlanFitter.fit(DayPlan(day: .nov18, blocks: blocks), meetings: meetings)
}

private func startedSession(_ events: [FocusEvent]) -> FocusSession? {
    guard events.count == 1, case let .started(session) = events[0] else { return nil }
    return session
}

private func endedRecord(_ events: [FocusEvent]) -> FocusRecord? {
    guard events.count == 1, case let .ended(record) = events[0] else { return nil }
    return record
}

struct FocusEngineTests {
    @Test func plannedStartsAndEnds() throws {
        let area = AreaID()
        let a = block("09:00", "10:00", area: area)
        let plan = fitted([a])
        var engine = FocusEngine()

        let session = try #require(startedSession(engine.advance(to: at("09:00"), plan: plan)))
        #expect(session.origin == .planned(a.id))
        #expect(session.areaID == area)
        #expect(session.start == at("09:00"))
        #expect(session.end == at("10:00"))

        let record = try #require(endedRecord(engine.advance(to: at("10:00"), plan: plan)))
        #expect(record.reason == .completed)
        #expect(record.actualEnd == at("10:00"))
        #expect(record.focused == 3600)
        #expect(record.session.id == session.id)
        #expect(engine.current == nil)
        #expect(engine.breakStartedAt == nil)
    }

    @Test func backToBack() {
        let a = block("09:00", "10:00")
        let b = block("10:00", "11:00")
        let plan = fitted([a, b])
        var engine = FocusEngine()
        _ = engine.advance(to: at("09:00"), plan: plan)

        let events = engine.advance(to: at("10:00"), plan: plan)
        #expect(events.count == 2)
        guard events.count == 2,
              case let .ended(record) = events[0],
              case let .started(session) = events[1] else { return }
        #expect(record.session.origin == .planned(a.id))
        #expect(session.origin == .planned(b.id))
        #expect(engine.pendingClosures.count == 1)
        #expect(engine.current == session)
    }

    @Test func splitBlockFocusedExcludesMeeting() throws {
        let a = block("09:00", "12:00")
        let plan = fitted([a], meetings: [DateInterval(start: at("10:00"), end: at("10:30"))])
        var engine = FocusEngine()
        _ = engine.advance(to: at("09:00"), plan: plan)
        #expect(engine.advance(to: at("10:15"), plan: plan) == [])

        let record = try #require(endedRecord(engine.advance(to: at("12:00"), plan: plan)))
        #expect(record.reason == .completed)
        #expect(record.focused == 9000)
    }

    @Test func focusedSoFarExcludesMeetingPause() {
        let a = block("09:00", "12:00")
        let plan = fitted([a], meetings: [DateInterval(start: at("10:00"), end: at("10:30"))])
        var engine = FocusEngine()
        #expect(engine.focusedSoFar(at: at("09:00")) == 0)
        _ = engine.advance(to: at("09:00"), plan: plan)
        _ = engine.advance(to: at("11:00"), plan: plan)
        #expect(engine.focusedSoFar(at: at("11:00")) == 5400)
    }

    @Test func focusedSoFarManualIsElapsed() {
        var engine = FocusEngine()
        _ = engine.startManual(areaID: AreaID(), intention: "", minutes: 25, now: at("14:00"))
        #expect(engine.focusedSoFar(at: at("14:10")) == 600)
    }

    @Test func manualThenBreak() throws {
        var engine = FocusEngine()
        let area = AreaID()
        let session = try #require(startedSession(
            engine.startManual(areaID: area, intention: "Read", minutes: 25, now: at("14:00"))
        ))
        #expect(session.origin == .manual)
        #expect(session.end == at("14:25"))

        let record = try #require(endedRecord(engine.advance(to: at("14:25"), plan: [])))
        #expect(record.reason == .completed)
        #expect(record.actualEnd == at("14:25"))
        #expect(record.focused == 1500)
        #expect(engine.breakStartedAt == at("14:25"))
        #expect(engine.current == nil)
        #expect(engine.pendingClosures == [record])
    }

    @Test func manualClampsDuration() throws {
        var engine = FocusEngine()
        let short = try #require(startedSession(
            engine.startManual(areaID: AreaID(), intention: "", minutes: 2, now: at("14:00"))
        ))
        #expect(short.end == at("14:05"))

        var other = FocusEngine()
        let long = try #require(startedSession(
            other.startManual(areaID: AreaID(), intention: "", minutes: 500, now: at("14:00"))
        ))
        #expect(long.end == at("17:00"))
    }

    @Test func planWinsOverManual() {
        let health = block("14:00", "15:00", intention: "Health")
        let plan = fitted([health])
        var engine = FocusEngine()
        _ = engine.startManual(areaID: AreaID(), intention: "Write", minutes: 60, now: at("13:30"))

        let events = engine.advance(to: at("14:00"), plan: plan)
        #expect(events.count == 1)
        guard events.count == 1, case let .tookOver(interrupted, by) = events[0] else { return }
        #expect(interrupted.reason == .tookOver)
        #expect(interrupted.actualEnd == at("14:00"))
        #expect(interrupted.focused == 1800)
        #expect(interrupted.session.origin == .manual)
        #expect(by.origin == .planned(health.id))
        #expect(by.start == at("14:00"))
        #expect(engine.current == by)
        #expect(engine.pendingClosures == [interrupted])
    }

    @Test func manualEndingAtBlockStartIsTakeover() {
        let health = block("14:00", "15:00", intention: "Health")
        let plan = fitted([health])
        var engine = FocusEngine()
        _ = engine.startManual(areaID: AreaID(), intention: "Write", minutes: 30, now: at("13:30"))

        let events = engine.advance(to: at("14:00"), plan: plan)
        #expect(events.count == 1)
        guard events.count == 1, case let .tookOver(interrupted, by) = events[0] else { return }
        #expect(interrupted.reason == .tookOver)
        #expect(interrupted.actualEnd == at("14:00"))
        #expect(by.origin == .planned(health.id))
        #expect(engine.breakStartedAt == nil)
        #expect(engine.current == by)
    }

    @Test func manualEndedBeforeBlockCompletes() {
        let health = block("14:00", "15:00", intention: "Health")
        let plan = fitted([health])
        var engine = FocusEngine()
        _ = engine.startManual(areaID: AreaID(), intention: "Write", minutes: 30, now: at("13:00"))

        let events = engine.advance(to: at("14:00"), plan: plan)
        #expect(events.count == 2)
        guard events.count == 2,
              case let .ended(record) = events[0],
              case let .started(session) = events[1] else { return }
        #expect(record.reason == .completed)
        #expect(record.actualEnd == at("13:30"))
        #expect(session.origin == .planned(health.id))
        #expect(engine.breakStartedAt == nil)
    }

    @Test func stoppedBlockDoesNotRestart() throws {
        let a = block("09:00", "10:00")
        let b = block("10:00", "11:00")
        let plan = fitted([a, b])
        var engine = FocusEngine()
        _ = engine.advance(to: at("09:00"), plan: plan)

        let stopped = try #require(endedRecord(engine.stop(now: at("09:20"), plan: plan)))
        #expect(stopped.reason == .stopped)
        #expect(stopped.actualEnd == at("09:20"))
        #expect(stopped.focused == 1200)
        #expect(engine.breakStartedAt == nil)

        #expect(engine.advance(to: at("09:21"), plan: plan) == [])
        #expect(engine.current == nil)

        let session = try #require(startedSession(engine.advance(to: at("10:00"), plan: plan)))
        #expect(session.origin == .planned(b.id))
    }

    @Test func launchMidBlockStartsNow() throws {
        let plan = fitted([block("09:00", "12:00")])
        var engine = FocusEngine()
        let session = try #require(startedSession(engine.advance(to: at("10:15"), plan: plan)))
        #expect(session.start == at("10:15"))
    }

    @Test func blockRemovedEndsAsStopped() throws {
        let a = block("09:00", "10:00")
        var engine = FocusEngine()
        _ = engine.advance(to: at("09:00"), plan: fitted([a]))

        let record = try #require(endedRecord(engine.advance(to: at("09:30"), plan: [])))
        #expect(record.reason == .stopped)
        #expect(record.actualEnd == at("09:30"))
        #expect(record.focused == 1800)
    }

    @Test func manualDuringPlannedStopsIt() {
        let a = block("09:00", "10:00")
        let plan = fitted([a])
        var engine = FocusEngine()
        _ = engine.advance(to: at("09:00"), plan: plan)

        let events = engine.startManual(areaID: AreaID(), intention: "Call", minutes: 30, now: at("09:15"))
        #expect(events.count == 2)
        guard events.count == 2,
              case let .ended(record) = events[0],
              case let .started(session) = events[1] else { return }
        #expect(record.reason == .stopped)
        #expect(record.focused == 900)
        #expect(session.origin == .manual)
        #expect(engine.advance(to: at("09:16"), plan: plan) == [])
        #expect(engine.current == session)
    }

    @Test func closeRemovesPendingRecord() throws {
        var engine = FocusEngine()
        _ = engine.startManual(areaID: AreaID(), intention: "", minutes: 25, now: at("14:00"))
        let record = try #require(endedRecord(engine.advance(to: at("14:25"), plan: [])))
        #expect(engine.close(recordID: record.session.id) == record)
        #expect(engine.pendingClosures.isEmpty)
        #expect(engine.close(recordID: record.session.id) == nil)
    }
}
