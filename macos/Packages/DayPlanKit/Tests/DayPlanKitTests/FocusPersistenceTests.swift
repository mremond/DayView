import Foundation
import Testing
@testable import DayPlanKit

private func block(_ start: String, _ end: String, on day: LocalDay = .nov18, id: BlockID = BlockID()) -> PlannedBlock {
    PlannedBlock(id: id, start: at(start, on: day), end: at(end, on: day), areaID: AreaID(), intention: "Focus")
}

private func fitted(_ blocks: [PlannedBlock], day: LocalDay = .nov18) -> [FittedBlock] {
    PlanFitter.fit(DayPlan(day: day, blocks: blocks), meetings: [])
}

private func roundTrip(_ engine: FocusEngine) throws -> FocusEngine {
    try JSONDecoder().decode(FocusEngine.self, from: JSONEncoder().encode(engine))
}

struct FocusPersistenceTests {
    @Test func roundTripMidBlock() throws {
        let plan = fitted([block("09:00", "10:00")])
        var engine = FocusEngine()
        _ = engine.advance(to: at("09:00"), plan: plan)
        _ = engine.stop(now: at("09:30"), plan: plan)
        _ = engine.advance(to: at("09:31"), plan: plan)
        var restored = try roundTrip(engine)

        let events = restored.advance(to: at("09:32"), plan: plan)

        #expect(events.isEmpty)
        #expect(restored.pendingClosures.count == 1)
    }

    @Test func roundTripRunningManual() throws {
        var engine = FocusEngine()
        _ = engine.startManual(areaID: AreaID(), intention: "Read", minutes: 25, now: at("10:00"))
        var restored = try roundTrip(engine)

        #expect(restored.current == engine.current)
        let events = restored.advance(to: at("10:25"), plan: [])
        guard events.count == 1, case let .ended(record) = events[0] else {
            Issue.record("expected one ended event, got \(events)")
            return
        }
        #expect(record.reason == .completed)
    }

    @Test func beginDayRestartsSameBlockID() {
        let day2 = LocalDay(year: 2026, month: 11, day: 19)
        let id = BlockID()
        let plan1 = fitted([block("09:00", "10:00", id: id)])
        let plan2 = fitted([block("09:00", "10:00", on: day2, id: id)], day: day2)

        var engine = FocusEngine()
        engine.beginDay(.nov18)
        _ = engine.advance(to: at("09:00"), plan: plan1)
        _ = engine.advance(to: at("10:00"), plan: plan1)
        var withoutBegin = engine

        engine.beginDay(day2)
        let events = engine.advance(to: at("09:00", on: day2), plan: plan2)
        guard events.count == 1, case .started = events[0] else {
            Issue.record("expected a start, got \(events)")
            return
        }

        #expect(withoutBegin.advance(to: at("09:00", on: day2), plan: plan2).isEmpty)
    }

    @Test func takeoverNotRetroactive() {
        var engine = FocusEngine()
        _ = engine.startManual(areaID: AreaID(), intention: "Read", minutes: 60, now: at("10:00"))
        _ = engine.advance(to: at("10:20"), plan: [])

        let events = engine.advance(to: at("10:21"), plan: fitted([block("10:00", "12:00")]))

        guard events.count == 1, case let .tookOver(record, _) = events[0] else {
            Issue.record("expected takeover, got \(events)")
            return
        }
        #expect(record.actualEnd == at("10:20"))
        #expect(record.focused == 20 * 60)
    }

    @Test func takeoverAtBlockStartUnchanged() {
        var engine = FocusEngine()
        _ = engine.startManual(areaID: AreaID(), intention: "Read", minutes: 60, now: at("13:30"))
        _ = engine.advance(to: at("13:59").addingTimeInterval(59), plan: [])

        let events = engine.advance(to: at("14:00"), plan: fitted([block("14:00", "15:00")]))

        guard events.count == 1, case let .tookOver(record, _) = events[0] else {
            Issue.record("expected takeover, got \(events)")
            return
        }
        #expect(record.actualEnd == at("14:00"))
    }
}
