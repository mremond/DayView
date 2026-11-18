import Foundation
import Testing
@testable import DayPlanKit

private func block(_ start: String, _ end: String) -> PlannedBlock {
    PlannedBlock(id: BlockID(), start: at(start), end: at(end), areaID: AreaID(), intention: "Focus")
}

private func plan(_ blocks: [PlannedBlock], meetings: [DateInterval] = []) -> (cues: [ScheduledCue], fitted: [FittedBlock]) {
    let fitted = PlanFitter.fit(DayPlan(day: .nov18, blocks: blocks), meetings: meetings)
    return (CueScheduler.schedule(fitted, meetings: meetings), fitted)
}

private func isStart(_ cue: ScheduledCue) -> Bool {
    if case .blockStart = cue.kind { true } else { false }
}

private func isResume(_ cue: ScheduledCue) -> Bool {
    if case .resume = cue.kind { true } else { false }
}

struct CueDeliveryTests {
    @Test func deliversOnCrossing() {
        let a = block("09:00", "10:00")
        let (cues, fitted) = plan([a])
        var delivery = CueDelivery()
        #expect(delivery.due(cues, fitted: fitted, now: at("08:59") + 59) == [])
        #expect(delivery.due(cues, fitted: fitted, now: at("09:00") + 1) == [cues[0]])
        #expect(cues[0].kind == .blockStart)
        #expect(delivery.due(cues, fitted: fitted, now: at("09:00") + 2) == [])
    }

    @Test func dropsLateCue() {
        let (cues, fitted) = plan([block("09:00", "09:30")])
        var delivery = CueDelivery()
        _ = delivery.due(cues, fitted: fitted, now: at("09:00"))
        #expect(delivery.due(cues, fitted: fitted, now: at("10:30")) == [])
    }

    @Test func launchMidBlockSpeaksStartOnce() {
        let (cues, fitted) = plan([block("09:00", "12:00")])
        var delivery = CueDelivery()
        let first = delivery.due(cues, fitted: fitted, now: at("10:15"))
        #expect(first.count == 1 && isStart(first[0]))
        #expect(delivery.due(cues, fitted: fitted, now: at("10:15") + 1) == [])
    }

    @Test func wakeAfterResumePrefersResume() {
        let a = block("09:00", "12:00")
        let (cues, fitted) = plan([a], meetings: [DateInterval(start: at("10:00"), end: at("10:30"))])
        var delivery = CueDelivery()
        _ = delivery.due(cues, fitted: fitted, now: at("09:10"))
        let result = delivery.due(cues, fitted: fitted, now: at("10:45"))
        #expect(result.count == 1 && isResume(result[0]))
    }

    @Test func noWakeCueDuringMeeting() {
        let a = block("09:00", "12:00")
        let (cues, fitted) = plan([a], meetings: [DateInterval(start: at("10:00"), end: at("10:30"))])
        var delivery = CueDelivery()
        #expect(delivery.due(cues, fitted: fitted, now: at("10:10")) == [])
        let result = delivery.due(cues, fitted: fitted, now: at("10:30") + 1)
        #expect(result.count == 1 && isResume(result[0]))
    }

    @Test func noBurstAfterSleep() {
        let c = block("12:00", "14:00")
        let (cues, fitted) = plan([block("08:00", "09:00"), block("09:00", "10:00"), c])
        var delivery = CueDelivery()
        _ = delivery.due(cues, fitted: fitted, now: at("08:00") - 1)
        let result = delivery.due(cues, fitted: fitted, now: at("13:00"))
        #expect(result.count == 1 && isStart(result[0]) && result[0].block?.id == c.id)
    }

    @Test func clockBackwardsDeliversNothing() {
        let (cues, fitted) = plan([block("09:00", "10:00")])
        var delivery = CueDelivery()
        _ = delivery.due(cues, fitted: fitted, now: at("09:30"))
        #expect(delivery.due(cues, fitted: fitted, now: at("08:00")) == [])
        #expect(delivery.due(cues, fitted: fitted, now: at("09:30")) == [])
    }
}
