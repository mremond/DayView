import Foundation
import Testing
@testable import DayPlanKit

private func block(_ start: String, _ end: String, intention: String = "Focus") -> PlannedBlock {
    PlannedBlock(id: BlockID(), start: at(start), end: at(end), areaID: AreaID(), intention: intention)
}

private func plan(_ blocks: [PlannedBlock]) -> DayPlan {
    DayPlan(day: .nov18, blocks: blocks)
}

private func fitted(_ plan: DayPlan) -> [FittedBlock] {
    PlanFitter.fit(plan, meetings: [])
}

/// A manual Focus 13:30–14:30 taken over at 14:00 by a Health block 14:00–15:00.
private func takenOver() -> (engine: FocusEngine, plan: DayPlan, health: PlannedBlock, record: FocusRecord)? {
    let health = block("14:00", "15:00", intention: "Health")
    let day = plan([health])
    var engine = FocusEngine()
    _ = engine.startManual(areaID: AreaID(), intention: "Write", minutes: 60, now: at("13:30"))
    let events = engine.advance(to: at("14:00"), plan: fitted(day))
    guard events.count == 1, case let .tookOver(record, _) = events[0] else { return nil }
    return (engine, day, health, record)
}

struct KeepGoingTests {
    @Test func offerInLastTwoMinutes() {
        let a = block("09:00", "10:00")
        let plan = fitted(plan([a]))
        var engine = FocusEngine()
        _ = engine.advance(to: at("09:00"), plan: plan)

        #expect(engine.keepGoingOffer(now: at("09:57"), plan: plan, meetings: []) == nil)
        #expect(engine.keepGoingOffer(now: at("09:58"), plan: plan, meetings: []) == .extendBlock(a.id))
    }

    @Test func noExtendWhenMeetingFollows() {
        let writing = block("09:00", "10:00", intention: "Writing")
        let health = block("13:00", "14:00", intention: "Health")
        let meetings = [DateInterval(start: at("10:00"), end: at("10:30"))]
        let plan = PlanFitter.fit(plan([writing, health]), meetings: meetings)
        var engine = FocusEngine()
        _ = engine.advance(to: at("09:00"), plan: plan)

        #expect(engine.keepGoingOffer(now: at("09:58"), plan: plan, meetings: meetings) == nil)
    }

    @Test func noExtendWhenMeetingTrimsEnd() {
        let writing = block("09:00", "12:00", intention: "Writing")
        let meetings = [DateInterval(start: at("11:30"), end: at("13:00"))]
        let plan = PlanFitter.fit(plan([writing]), meetings: meetings)
        var engine = FocusEngine()
        _ = engine.advance(to: at("09:00"), plan: plan)

        #expect(engine.keepGoingOffer(now: at("11:28"), plan: plan, meetings: meetings) == nil)
    }

    @Test func extendWhenMeetingIsLater() {
        let writing = block("09:00", "10:00", intention: "Writing")
        let meetings = [DateInterval(start: at("10:20"), end: at("10:40"))]
        let plan = PlanFitter.fit(plan([writing]), meetings: meetings)
        var engine = FocusEngine()
        _ = engine.advance(to: at("09:00"), plan: plan)

        #expect(engine.keepGoingOffer(now: at("09:58"), plan: plan, meetings: meetings) == .extendBlock(writing.id))
    }

    @Test func extendedBlockKeepsSameSession() throws {
        let a = block("09:00", "10:00")
        var day = plan([a])
        var engine = FocusEngine()
        _ = engine.advance(to: at("09:00"), plan: fitted(day))
        let session = try #require(engine.current)

        day = day.keepingGoing(blockID: a.id, by: 900, calendar: paris)
        let extended = fitted(day)
        #expect(engine.advance(to: at("10:00"), plan: extended) == [])
        #expect(engine.current?.id == session.id)
        #expect(engine.current?.end == at("10:15"))

        let events = engine.advance(to: at("10:15"), plan: extended)
        #expect(events.count == 1)
        guard events.count == 1, case let .ended(record) = events[0] else { return }
        #expect(record.session.id == session.id)
        #expect(record.reason == .completed)
        #expect(record.actualEnd == at("10:15"))
    }

    @Test func reopenAfterTakeover() throws {
        var (engine, day, health, record) = try #require(takenOver())
        #expect(engine.keepGoingOffer(now: at("14:01"), plan: fitted(day), meetings: [])
            == .reopenInterrupted(record, blockID: health.id))

        day = day.shiftingFrom(blockID: health.id, by: 900, calendar: paris)
        let shifted = fitted(day)
        let events = engine.reopenInterrupted(until: at("14:15"))
        var manual = record.session
        manual.end = at("14:15")
        #expect(events == [.reopened(manual, discardedRecordID: record.session.id)])
        #expect(engine.pendingClosures.isEmpty)
        #expect(engine.current == manual)
        #expect(engine.current?.origin == .manual)
        #expect(engine.keepGoingOffer(now: at("14:02"), plan: shifted, meetings: []) == nil)

        #expect(engine.advance(to: at("14:05"), plan: shifted) == [])
        let later = engine.advance(to: at("14:15"), plan: shifted)
        #expect(later.count == 1)
        guard later.count == 1, case let .tookOver(interrupted, by) = later[0] else { return }
        #expect(interrupted.session.id == record.session.id)
        #expect(interrupted.actualEnd == at("14:15"))
        #expect(by.origin == .planned(health.id))
        #expect(by.start == at("14:15"))
        #expect(by.end == at("15:15"))
        #expect(engine.pendingClosures == [interrupted])
    }

    @Test func noReopenAfterWindow() throws {
        let (engine, day, _, _) = try #require(takenOver())
        #expect(engine.keepGoingOffer(now: at("14:03"), plan: fitted(day), meetings: []) == nil)
    }

    @Test func noReopenOnceThePlannedFocusEnded() throws {
        var (engine, day, _, _) = try #require(takenOver())
        _ = engine.stop(now: at("14:01"), plan: fitted(day))
        #expect(engine.keepGoingOffer(now: at("14:01"), plan: fitted(day), meetings: []) == nil)
        #expect(engine.reopenInterrupted(until: at("14:15")) == [])
    }
}
