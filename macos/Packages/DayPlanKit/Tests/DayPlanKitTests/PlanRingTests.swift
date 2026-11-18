import Foundation
import Testing
@testable import DayPlanKit

private let area = AreaID()

private func fitted(_ parts: [(String, String)]) -> FittedBlock {
    let block = PlannedBlock(
        id: BlockID(), start: at(parts.first!.0), end: at(parts.last!.1), areaID: area, intention: ""
    )
    return FittedBlock(
        block: block, parts: parts.map { DateInterval(start: at($0.0), end: at($0.1)) }, notes: []
    )
}

private func arcs(_ blocks: [FittedBlock]) -> [PlanArc] {
    PlanRing.arcs(blocks, windowStart: at("08:00"), windowEnd: at("20:00"))
}

struct PlanRingTests {
    @Test func arcAngles() {
        let result = arcs([fitted([("08:00", "11:00")]), fitted([("14:00", "20:00")])])
        #expect(result.count == 2)
        #expect(result[0].startAngleDegrees == -90 && result[0].sweepDegrees == 90)
        #expect(result[1].startAngleDegrees == 90 && result[1].sweepDegrees == 180)
        #expect(result[0].areaID == area)
    }

    @Test func clipping() {
        let early = arcs([fitted([("06:00", "09:00")])])
        #expect(early.count == 1)
        #expect(early[0].startAngleDegrees == -90 && early[0].sweepDegrees == 30)
        #expect(arcs([fitted([("21:00", "22:00")])]).isEmpty)

        let split = fitted([("09:00", "10:00"), ("10:30", "12:00")])
        let result = arcs([split])
        #expect(result.count == 2)
        #expect(result[0].blockID == split.block.id && result[1].blockID == split.block.id)
    }

    @Test func hitTest() {
        let a = PlanArc(blockID: BlockID(), areaID: area, startAngleDegrees: -90, sweepDegrees: 90)
        let b = PlanArc(blockID: BlockID(), areaID: area, startAngleDegrees: 90, sweepDegrees: 180)
        #expect(PlanRing.arcIndex(at: -45, in: [a, b]) == 0)
        #expect(PlanRing.arcIndex(at: 180, in: [a, b]) == 1)
        #expect(PlanRing.arcIndex(at: 2, in: [a, b]) == 0)
        #expect(PlanRing.arcIndex(at: 45, in: [a, b]) == nil)
        #expect(PlanRing.arcIndex(at: -100, in: [a, b]) == 1)
    }
}

struct PlanRingWindowTests {
    private func wall(_ day: LocalDay, _ hour: Int, _ minute: Int = 0) -> Date {
        paris.date(from: DateComponents(
            year: day.year, month: day.month, day: day.day, hour: hour, minute: minute
        ))!
    }

    @Test(arguments: [LocalDay(year: 2026, month: 3, day: 29), LocalDay(year: 2026, month: 10, day: 25)])
    func windowKeepsWallClockOnDSTDays(_ day: LocalDay) {
        let window = PlanRing.window(day: day, startMinute: 8 * 60, endMinute: 20 * 60, calendar: paris)
        #expect(window.start == wall(day, 8))
        #expect(window.end == wall(day, 20))
    }

    @Test func windowClampsLikeKotlin() {
        let day = LocalDay.nov18
        let late = PlanRing.window(day: day, startMinute: 23 * 60 + 50, endMinute: 23 * 60 + 55, calendar: paris)
        #expect(late.start == wall(day, 23, 29))
        #expect(late.end == wall(day, 23, 59))
        let early = PlanRing.window(day: day, startMinute: -30, endMinute: 10, calendar: paris)
        #expect(early.start == wall(day, 0))
        #expect(early.end == wall(day, 0, 30))
        let long = PlanRing.window(day: day, startMinute: 8 * 60, endMinute: 2000, calendar: paris)
        #expect(long.end == wall(day, 23, 59))
    }
}
