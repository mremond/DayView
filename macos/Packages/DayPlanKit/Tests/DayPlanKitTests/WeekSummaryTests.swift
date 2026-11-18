import Foundation
import Testing
@testable import DayPlanKit

private func day(_ m: Int, _ d: Int, y: Int = 2026) -> LocalDay { LocalDay(year: y, month: m, day: d) }

private func block(_ area: AreaID, _ s: String, _ e: String, on d: LocalDay) -> PlannedBlock {
    PlannedBlock(id: BlockID(), start: at(s, on: d), end: at(e, on: d), areaID: area, intention: "")
}

private func compute(
    today: LocalDay, accepted: [LocalDay: AcceptedPlan] = [:], focus: [FocusTime] = [],
    ignored: [LocalDay: Int] = [:], order: [AreaID] = []
) -> WeekSummary {
    WeekSummary.compute(
        today: today, calendar: paris, accepted: accepted, focus: focus, ignored: ignored, areaOrder: order)
}

@Test func weekFromMonday() {
    #expect(compute(today: day(11, 18)).days == [day(11, 16), day(11, 17), day(11, 18)])
    #expect(compute(today: day(11, 16)).days == [day(11, 16)])
}

@Test func plannedAndFocus() {
    let writing = AreaID(), health = AreaID()
    let mon = day(11, 16), tue = day(11, 17), wed = day(11, 18)
    let accepted = [
        mon: AcceptedPlan(plan: DayPlan(day: mon, blocks: [block(writing, "09:00", "12:00", on: mon)]), reviewed: true),
        tue: AcceptedPlan(
            plan: DayPlan(day: tue, blocks: [block(writing, "09:00", "10:00", on: tue), block(health, "18:00", "19:00", on: tue)]),
            reviewed: false)
    ]
    let focus = [
        FocusTime(day: mon, areaID: writing, seconds: 9000),
        FocusTime(day: tue, areaID: health, seconds: 3000),
        FocusTime(day: wed, areaID: writing, seconds: 1200)
    ]
    let week = compute(today: wed, accepted: accepted, focus: focus, ignored: [tue: 2], order: [health, writing])
    #expect(week.areas == [
        AreaWeekLine(areaID: health, planned: 3600, focused: 3000),
        AreaWeekLine(areaID: writing, planned: 14400, focused: 10200)
    ])
    #expect(week.signals == [
        DaySignals(day: mon, review: .reviewed, ignoredCues: 0),
        DaySignals(day: tue, review: .autoAccepted, ignoredCues: 2),
        DaySignals(day: wed, review: .noPlan, ignoredCues: 0)
    ])
}

@Test func unorderedAreasAndEmptyLines() {
    let a = AreaID(), b = AreaID(), unused = AreaID()
    let focus = [FocusTime(day: day(11, 16), areaID: b, seconds: 60)]
    let tue = day(11, 17)
    let accepted = [tue: AcceptedPlan(plan: DayPlan(day: tue, blocks: [block(a, "09:00", "10:00", on: tue)]), reviewed: true)]
    let week = compute(today: tue, accepted: accepted, focus: focus, order: [unused])
    #expect(Set(week.areas.map(\.areaID)) == [a, b])
    #expect(!week.areas.contains { $0.areaID == unused })
    #expect(week.areas.count == 2)
}

@Test func dstWeek() {
    #expect(compute(today: day(4, 1)).days == [day(3, 30), day(3, 31), day(4, 1)])
    let sunday = compute(today: day(3, 29)).days
    #expect(sunday.count == 7)
    #expect(sunday.first == day(3, 23))
    #expect(sunday.last == day(3, 29))
}
