import Foundation
import Testing
@testable import DayPlanKit

private func manualEngine(start: String = "10:00", minutes: Int = 25) -> (FocusEngine, FocusSession) {
    var engine = FocusEngine()
    let events = engine.startManual(areaID: AreaID(), intention: "Write", minutes: minutes, now: at(start))
    guard case let .started(session) = events.last! else { fatalError("no session") }
    return (engine, session)
}

struct OvertimeTests {
    @Test func manualContinuesPastTerm() {
        var (engine, _) = manualEngine()
        let events = engine.advance(to: at("10:30"), plan: [])
        #expect(events.isEmpty)
        #expect(engine.current != nil)
        #expect(engine.overtime(at: at("10:30")) == 300)
        #expect(engine.overtime(at: at("10:20")) == nil)
        #expect(engine.focusedSoFar(at: at("10:30")) == 1800)
    }

    @Test func stopInOvertimeCompletes() throws {
        var (engine, _) = manualEngine()
        _ = engine.advance(to: at("10:30"), plan: [])
        let events = engine.stop(now: at("10:40"), plan: [])
        guard case let .ended(record) = try #require(events.first) else { Issue.record("no end"); return }
        #expect(record.reason == .completed)
        #expect(record.actualEnd == at("10:40"))
        #expect(record.focused == 2400)
        #expect(engine.breakStartedAt == at("10:40"))
    }

    @Test func stopBeforeTermStops() throws {
        var (engine, _) = manualEngine()
        let events = engine.stop(now: at("10:10"), plan: [])
        guard case let .ended(record) = try #require(events.first) else { Issue.record("no end"); return }
        #expect(record.reason == .stopped)
        #expect(record.focused == 600)
        #expect(engine.breakStartedAt == at("10:10"))
    }

    @Test func takeoverInOvertime() throws {
        var (engine, manual) = manualEngine()
        let block = PlannedBlock(id: BlockID(), start: at("11:00"), end: at("12:00"), areaID: AreaID(), intention: "Plan")
        let plan = PlanFitter.fit(DayPlan(day: .nov18, blocks: [block]), meetings: [])
        var tookOver: FocusRecord?
        var t = at("10:00")
        while t <= at("11:00") {
            for event in engine.advance(to: t, plan: plan) {
                if case let .tookOver(interrupted, _) = event { tookOver = interrupted }
            }
            t = t.addingTimeInterval(1)
        }
        let record = try #require(tookOver)
        #expect(record.session.id == manual.id)
        #expect(record.actualEnd == at("11:00"))
        #expect(record.reason == .tookOver)
        #expect(record.focused == 3600)
        #expect(engine.breakStartedAt == nil)
    }

    @Test func restoredInOvertime() throws {
        let (engine, _) = manualEngine()
        var restored = try JSONDecoder().decode(FocusEngine.self, from: JSONEncoder().encode(engine))
        let events = restored.advance(to: at("11:00"), plan: [])
        #expect(events.isEmpty)
        #expect(restored.current != nil)
        #expect(restored.overtime(at: at("11:00")) == 2100)
    }

    @Test func midnightKeepsOvertimeManual() {
        let day = LocalDay.nov18
        var engine = FocusEngine()
        engine.beginDay(day, calendar: paris)
        _ = engine.startManual(areaID: AreaID(), intention: "Late", minutes: 25, now: at("23:30"))
        let next = LocalDay(year: 2026, month: 11, day: 19)
        let events = engine.beginDay(next, calendar: paris)
        #expect(events.isEmpty)
        #expect(engine.current != nil)
        #expect(engine.overtime(at: at("00:10", on: next)) == 900)
    }

    @Test func plannedHasNoOvertime() {
        let block = PlannedBlock(id: BlockID(), start: at("10:00"), end: at("11:00"), areaID: AreaID(), intention: "P")
        let plan = PlanFitter.fit(DayPlan(day: .nov18, blocks: [block]), meetings: [])
        var engine = FocusEngine()
        _ = engine.advance(to: at("10:30"), plan: plan)
        #expect(engine.overtime(at: at("10:30")) == nil)
    }

    private func manualSession() -> FocusSession {
        FocusSession(areaID: AreaID(), intention: "x", origin: .manual, start: at("10:00"), end: at("10:25"))
    }

    @Test func reminderFiresOnceAtDouble() {
        var reminder = OvertimeReminder()
        let session = manualSession()
        var fired: [Date] = []
        var t = at("10:00")
        while t <= at("11:00") {
            if reminder.observe(now: t, session: session) { fired.append(t) }
            t = t.addingTimeInterval(1)
        }
        #expect(fired == [at("10:50")])
    }

    @Test func reminderLateIsSilent() {
        var reminder = OvertimeReminder()
        let session = manualSession()
        let fired2529 = reminder.observe(now: at("10:40"), session: session)
        #expect(!fired2529)
        let fired2755 = reminder.observe(now: at("12:00"), session: session)
        #expect(!fired2755)
    }

    @Test func reminderIgnoresPlanned() {
        var reminder = OvertimeReminder()
        let session = FocusSession(areaID: AreaID(), intention: "x", origin: .planned(BlockID()), start: at("10:00"), end: at("10:25"))
        var t = at("10:00")
        while t <= at("11:00") {
            let fired2466 = reminder.observe(now: t, session: session)
            #expect(!fired2466)
            t = t.addingTimeInterval(30)
        }
        let fired3210 = reminder.observe(now: at("10:00"), session: nil)
        #expect(!fired3210)
    }

    @Test func reminderNewSessionResets() {
        var reminder = OvertimeReminder()
        let first = manualSession()
        _ = reminder.observe(now: at("10:49"), session: first)
        let fired2364 = reminder.observe(now: at("10:50"), session: first)
        #expect(fired2364)
        let fired8528 = reminder.observe(now: at("10:51"), session: first)
        #expect(!fired8528)
        let second = FocusSession(areaID: AreaID(), intention: "y", origin: .manual, start: at("11:00"), end: at("11:25"))
        _ = reminder.observe(now: at("11:49"), session: second)
        let fired481 = reminder.observe(now: at("11:50"), session: second)
        #expect(fired481)
    }

    @Test func reminderCodableRoundTrip() throws {
        var reminder = OvertimeReminder()
        let session = manualSession()
        _ = reminder.observe(now: at("10:50"), session: session)
        var restored = try JSONDecoder().decode(OvertimeReminder.self, from: JSONEncoder().encode(reminder))
        let fired6510 = restored.observe(now: at("10:50").addingTimeInterval(1), session: session)
        #expect(!fired6510)
    }
}
