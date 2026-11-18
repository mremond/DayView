import Foundation
import Testing
@testable import DayPlanKit

private let area = AreaID()

private func block(_ start: String, _ end: String, on day: LocalDay = .nov18) -> PlannedBlock {
    PlannedBlock(id: BlockID(), start: at(start, on: day), end: at(end, on: day), areaID: area, intention: "")
}

private func spans(_ plan: DayPlan) -> [[Date]] {
    plan.blocks.map { [$0.start, $0.end] }
}

struct PlanModelTests {
    @Test func instantiateKeepsOnlyTodaysWeekday() {
        let template = [
            TemplateBlock(id: BlockID(), weekdayISO: 3, startMinute: 540, endMinute: 720, areaID: area, intention: "Writing"),
            TemplateBlock(id: BlockID(), weekdayISO: 4, startMinute: 540, endMinute: 600, areaID: area, intention: "Other")
        ]
        let plan = DayPlan.instantiate(template: template, day: .nov18, calendar: paris)
        #expect(plan.blocks.count == 1)
        #expect(plan.blocks[0].start == at("09:00"))
        #expect(plan.blocks[0].end == at("12:00"))
        #expect(plan.blocks[0].intention == "Writing")
        #expect(plan.blocks[0].id == template[0].id)
    }

    @Test func instantiateEndOfDay() {
        let template = [
            TemplateBlock(id: BlockID(), weekdayISO: 3, startMinute: 1320, endMinute: 1440, areaID: area, intention: "")
        ]
        let plan = DayPlan.instantiate(template: template, day: .nov18, calendar: paris)
        #expect(plan.blocks[0].end == LocalDay.nov18.nextMidnight(in: paris))
    }

    @Test func instantiateOnDstGap() {
        let sunday = LocalDay(year: 2026, month: 3, day: 29)
        #expect(sunday.weekdayISO(in: paris) == 7)
        let template = [
            TemplateBlock(id: BlockID(), weekdayISO: 7, startMinute: 150, endMinute: 240, areaID: area, intention: "")
        ]
        let plan = DayPlan.instantiate(template: template, day: sunday, calendar: paris)
        let start = paris.dateComponents([.hour, .minute], from: plan.blocks[0].start)
        let end = paris.dateComponents([.hour, .minute], from: plan.blocks[0].end)
        #expect(start.hour == 3 && start.minute == 0)
        #expect(end.hour == 4 && end.minute == 0)
    }

    @Test func localDayRoundTrip() {
        #expect(LocalDay(containing: at("23:30"), calendar: paris) == .nov18)
        #expect(LocalDay.nov18.weekdayISO(in: paris) == 3)
    }

    @Test func keepingGoingExtendsAndShiftsLater() {
        let a = block("09:00", "10:00"), b = block("10:00", "11:00"), c = block("14:00", "15:00")
        let plan = DayPlan(day: .nov18, blocks: [a, b, c]).keepingGoing(blockID: a.id, by: 900, calendar: paris)
        #expect(spans(plan) == [[at("09:00"), at("10:15")], [at("10:15"), at("11:15")], [at("14:15"), at("15:15")]])
    }

    @Test func keepingGoingDropsPastMidnight() {
        let a = block("22:00", "23:50")
        let b = PlannedBlock(
            id: BlockID(), start: at("23:50"), end: LocalDay.nov18.nextMidnight(in: paris), areaID: area, intention: ""
        )
        let plan = DayPlan(day: .nov18, blocks: [a, b]).keepingGoing(blockID: a.id, by: 900, calendar: paris)
        #expect(!plan.blocks.contains { $0.id == b.id })
        #expect(plan.blocks.map(\.id) == [a.id])
        #expect(plan.blocks[0].end == LocalDay.nov18.nextMidnight(in: paris))
    }

    @Test func shiftingFromMovesBlockAndLater() {
        let a = block("09:00", "10:00"), b = block("10:00", "11:00")
        let plan = DayPlan(day: .nov18, blocks: [a, b]).shiftingFrom(blockID: b.id, by: 900, calendar: paris)
        #expect(spans(plan) == [[at("09:00"), at("10:00")], [at("10:15"), at("11:15")]])
    }
}
