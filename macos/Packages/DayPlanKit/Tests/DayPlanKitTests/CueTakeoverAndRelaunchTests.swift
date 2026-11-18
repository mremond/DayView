import Foundation
import Testing
@testable import DayPlanKit

private let writing = AreaID()
private let health = AreaID()

private func english() -> CueText {
    let formatter = DateFormatter()
    formatter.dateFormat = "HH:mm"
    formatter.timeZone = TimeZone(identifier: "Europe/Paris")
    return CueText(
        language: .en,
        areaName: { $0 == writing ? "Writing" : "Health" },
        time: { formatter.string(from: $0) }
    )
}

private func block(_ start: String, _ end: String, area: AreaID = health, id: BlockID = BlockID()) -> PlannedBlock {
    PlannedBlock(id: id, start: at(start), end: at(end), areaID: area, intention: "")
}

private func fitted(_ blocks: [PlannedBlock]) -> [FittedBlock] {
    PlanFitter.fit(DayPlan(day: .nov18, blocks: blocks), meetings: [])
}

private func roundTrip(_ engine: FocusEngine) throws -> FocusEngine {
    try JSONDecoder().decode(FocusEngine.self, from: JSONEncoder().encode(engine))
}

/// One app tick, as FocusModel runs it: engine, delivery, takeover supplement, sentences.
private func tick(
    _ engine: inout FocusEngine, _ delivery: inout CueDelivery, plan: [FittedBlock], now: Date,
    launch: Bool = false
) -> [String] {
    engine.beginDay(.nov18, calendar: paris)
    let loaded = engine
    let events = engine.advance(to: now, plan: plan)
    var due = delivery.due(CueScheduler.schedule(plan, meetings: []), fitted: plan, now: now)
    if launch { due = CueDelivery.dropAlreadyStarted(due, startedBy: loaded) }
    due = TakeoverCues.supplement(due: due, events: events, fitted: plan, now: now)
    return CueComposer.sentences(due: due, events: events, text: english())
}

struct TakeoverCueTests {
    @Test func blockAppearingMidFocusIsAnnouncedOnce() {
        var engine = FocusEngine()
        var delivery = CueDelivery()
        _ = engine.startManual(areaID: writing, intention: "", minutes: 60, now: at("10:00"))
        for minute in 0...20 {
            #expect(tick(&engine, &delivery, plan: [], now: at("10:00") + Double(minute * 60)).isEmpty)
        }
        let plan = fitted([block("10:00", "12:00")])

        #expect(tick(&engine, &delivery, plan: plan, now: at("10:21")) == ["10:21. Health. Your Writing focus ends here."])
        #expect(tick(&engine, &delivery, plan: plan, now: at("10:22")).isEmpty)
    }

    @Test func scheduledTakeoverIsNotDoubled() {
        var engine = FocusEngine()
        var delivery = CueDelivery()
        let plan = fitted([block("14:00", "15:00")])
        _ = engine.startManual(areaID: writing, intention: "", minutes: 60, now: at("13:30"))
        _ = tick(&engine, &delivery, plan: plan, now: at("13:59"))

        #expect(tick(&engine, &delivery, plan: plan, now: at("14:00")) == ["14:00. Health. Your Writing focus ends here."])
    }
}

struct RelaunchCueTests {
    @Test func stoppedBlockIsSilentAfterRelaunch() throws {
        let plan = fitted([block("09:00", "12:00")])
        var engine = FocusEngine()
        var delivery = CueDelivery()
        _ = tick(&engine, &delivery, plan: plan, now: at("09:00"))
        _ = engine.stop(now: at("09:30"), plan: plan)

        var restored = try roundTrip(engine)
        var fresh = CueDelivery()
        #expect(tick(&restored, &fresh, plan: plan, now: at("10:00"), launch: true).isEmpty)
        #expect(tick(&restored, &fresh, plan: plan, now: at("10:01")).isEmpty)
    }

    @Test func runningBlockIsSilentAfterRelaunch() throws {
        let plan = fitted([block("09:00", "12:00")])
        var engine = FocusEngine()
        var delivery = CueDelivery()
        _ = tick(&engine, &delivery, plan: plan, now: at("09:00"))

        var restored = try roundTrip(engine)
        var fresh = CueDelivery()
        #expect(tick(&restored, &fresh, plan: plan, now: at("10:00"), launch: true).isEmpty)
        #expect(restored.current != nil)
    }

    @Test func blockStartedWhileClosedIsAnnouncedOnRelaunch() throws {
        let plan = fitted([block("09:00", "12:00")])
        var engine = FocusEngine()
        var delivery = CueDelivery()
        _ = tick(&engine, &delivery, plan: plan, now: at("08:30"))

        var restored = try roundTrip(engine)
        var fresh = CueDelivery()
        #expect(tick(&restored, &fresh, plan: plan, now: at("10:00"), launch: true) == ["09:00. Health."])
    }

    @Test func dropKeepsOtherCues() {
        let started = block("09:00", "10:00")
        let other = block("10:00", "11:00")
        let plan = fitted([started, other])
        var engine = FocusEngine()
        _ = engine.advance(to: at("09:00"), plan: plan)
        let cues = CueScheduler.schedule(plan, meetings: [])

        let kept = CueDelivery.dropAlreadyStarted(cues, startedBy: engine)

        #expect(!kept.contains { $0.block?.id == started.id && $0.kind == .blockStart })
        #expect(kept.contains { $0.block?.id == started.id && $0.kind != .blockStart })
        #expect(kept.contains { $0.block?.id == other.id && $0.kind == .blockStart })
    }
}
