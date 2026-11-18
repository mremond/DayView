import Foundation
import Testing
@testable import DayPlanKit

private func block(_ start: String, _ end: String) -> PlannedBlock {
    PlannedBlock(id: BlockID(), start: at(start), end: at(end), areaID: AreaID(), intention: "Focus")
}

struct KeepGoingApplyTests {
    @Test func keepGoingExtend() {
        let a = block("09:00", "10:00")
        let b = block("10:00", "11:00")
        let result = KeepGoing.apply(
            .extendBlock(a.id), to: DayPlan(day: .nov18, blocks: [a, b]), meetings: [], calendar: paris
        )

        let byID = Dictionary(uniqueKeysWithValues: result.plan.blocks.map { ($0.id, $0) })
        #expect(byID[a.id]?.end == at("10:15"))
        #expect(byID[b.id]?.start == at("10:15"))
        #expect(byID[b.id]?.end == at("11:15"))
        #expect(result.reopenUntil == nil)
    }

    @Test func keepGoingReopen() {
        let a = block("14:00", "15:00")
        let record = FocusRecord(
            session: FocusSession(areaID: AreaID(), intention: "", origin: .manual, start: at("13:30"), end: at("14:30")),
            actualEnd: at("14:00"), focused: 1800, reason: .tookOver
        )
        let result = KeepGoing.apply(
            .reopenInterrupted(record, blockID: a.id), to: DayPlan(day: .nov18, blocks: [a]), meetings: [], calendar: paris
        )
        #expect(result.plan.blocks.first?.start == at("14:15"))
        #expect(result.plan.blocks.first?.end == at("15:15"))
        #expect(result.reopenUntil == at("14:15"))

        let late = block("23:30", "24:00")
        let dropped = KeepGoing.apply(
            .reopenInterrupted(record, blockID: late.id), to: DayPlan(day: .nov18, blocks: [late]), meetings: [], calendar: paris
        )
        #expect(dropped.plan.blocks.isEmpty)
        #expect(dropped.reopenUntil == nil)
    }

    @Test func keepGoingReopenAroundMeeting() {
        let a = block("14:00", "15:00")
        let record = FocusRecord(
            session: FocusSession(areaID: AreaID(), intention: "", origin: .manual, start: at("13:30"), end: at("14:30")),
            actualEnd: at("14:00"), focused: 1800, reason: .tookOver
        )
        let meetings = [DateInterval(start: at("14:10"), end: at("14:30"))]
        let result = KeepGoing.apply(
            .reopenInterrupted(record, blockID: a.id), to: DayPlan(day: .nov18, blocks: [a]), meetings: meetings, calendar: paris
        )
        #expect(result.plan.blocks.first?.start == at("14:15"))
        #expect(result.reopenUntil == at("14:30"))
    }
}
