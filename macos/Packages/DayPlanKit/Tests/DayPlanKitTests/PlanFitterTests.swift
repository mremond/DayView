import Foundation
import Testing
@testable import DayPlanKit

private func interval(_ start: String, _ end: String) -> DateInterval {
    DateInterval(start: at(start), end: at(end))
}

private func fit(_ start: String, _ end: String, meetings: [DateInterval]) -> FittedBlock {
    let block = PlannedBlock(id: BlockID(), start: at(start), end: at(end), areaID: AreaID(), intention: "Writing")
    let plan = DayPlan(day: .nov18, blocks: [block])
    return PlanFitter.fit(plan, meetings: meetings)[0]
}

struct PlanFitterTests {
    @Test func noMeetingsKeepsBlock() {
        let fitted = fit("09:00", "12:00", meetings: [])
        #expect(fitted.parts == [interval("09:00", "12:00")])
        #expect(fitted.notes.isEmpty)
        #expect(fitted.start == at("09:00"))
        #expect(fitted.end == at("12:00"))
    }

    @Test func meetingInsideSplits() {
        let fitted = fit("09:00", "12:00", meetings: [interval("10:00", "10:30")])
        #expect(fitted.parts == [interval("09:00", "10:00"), interval("10:30", "12:00")])
        #expect(fitted.notes == [.splitAround(interval("10:00", "10:30"))])
    }

    @Test func meetingAtEdgeTrims() {
        let fitted = fit("09:00", "12:00", meetings: [interval("11:30", "13:00")])
        #expect(fitted.parts == [interval("09:00", "11:30")])
        #expect(fitted.notes == [.trimmed])
    }

    @Test func shortPieceDropped() {
        let fitted = fit("09:00", "10:00", meetings: [interval("09:05", "10:00")])
        #expect(fitted.isDropped)
        #expect(fitted.notes == [.dropped])
        #expect(fitted.start == nil)
        #expect(fitted.end == nil)
    }

    @Test func shortPieceDiscardedTrims() {
        let fitted = fit("09:00", "12:00", meetings: [interval("09:05", "10:00")])
        #expect(fitted.parts == [interval("10:00", "12:00")])
        #expect(fitted.notes == [.trimmed])
    }

    @Test func fullyCoveredDropped() {
        let fitted = fit("09:00", "10:00", meetings: [interval("08:00", "11:00")])
        #expect(fitted.isDropped)
        #expect(fitted.notes == [.dropped])
    }

    @Test func overlappingMeetingsMergedFirst() {
        let fitted = fit("09:00", "12:00", meetings: [interval("10:00", "10:30"), interval("10:20", "10:45")])
        #expect(fitted.parts == [interval("09:00", "10:00"), interval("10:45", "12:00")])
        #expect(fitted.notes == [.splitAround(interval("10:00", "10:45"))])
    }

    @Test func touchingMeetingsMerged() {
        let fitted = fit("09:00", "12:00", meetings: [interval("10:30", "11:00"), interval("10:00", "10:30")])
        #expect(fitted.parts == [interval("09:00", "10:00"), interval("11:00", "12:00")])
    }

    @Test func meetingOutsideBlockIgnored() {
        let fitted = fit("09:00", "12:00", meetings: [interval("12:00", "13:00"), interval("08:00", "09:00")])
        #expect(fitted.parts == [interval("09:00", "12:00")])
        #expect(fitted.notes.isEmpty)
    }

    @Test func pausedBetweenParts() {
        let fitted = fit("09:00", "12:00", meetings: [interval("10:00", "10:30")])
        #expect(fitted.isRunning(at: at("10:15")))
        #expect(fitted.isPaused(at: at("10:15")))
        #expect(fitted.isRunning(at: at("09:30")))
        #expect(!fitted.isPaused(at: at("09:30")))
        #expect(!fitted.isRunning(at: at("12:00")))
        #expect(!fitted.isRunning(at: at("08:59")))
    }

    @Test func droppedBlockNeverRunning() {
        let fitted = fit("09:00", "10:00", meetings: [interval("08:00", "11:00")])
        #expect(!fitted.isRunning(at: at("09:30")))
        #expect(!fitted.isPaused(at: at("09:30")))
    }

    @Test func keepsOrderAndDroppedBlocks() {
        let a = PlannedBlock(id: BlockID(), start: at("09:00"), end: at("10:00"), areaID: AreaID(), intention: "A")
        let b = PlannedBlock(id: BlockID(), start: at("10:00"), end: at("11:00"), areaID: AreaID(), intention: "B")
        let fitted = PlanFitter.fit(DayPlan(day: .nov18, blocks: [b, a]), meetings: [interval("09:00", "10:00")])
        #expect(fitted.map(\.block.id) == [a.id, b.id])
        #expect(fitted[0].isDropped)
        #expect(!fitted[1].isDropped)
    }
}
