import Foundation
import Testing
@testable import DayPlanKit

private let area = AreaID()

private func tmpl(_ start: Int, _ end: Int, weekday: Int = 3) -> TemplateBlock {
    TemplateBlock(id: BlockID(), weekdayISO: weekday, startMinute: start, endMinute: end, areaID: area, intention: "")
}

private func accepted(_ start: String, _ end: String, reviewed: Bool = true) -> AcceptedPlan {
    let block = PlannedBlock(id: BlockID(), start: at(start), end: at(end), areaID: area, intention: "")
    return AcceptedPlan(plan: DayPlan(day: .nov18, blocks: [block]), reviewed: reviewed)
}

private func state(
    accepted: AcceptedPlan? = nil, template: [TemplateBlock] = [], meetings: [DateInterval] = []
) -> TodayPlanState {
    TodayPlanner.state(day: .nov18, accepted: accepted, template: template, meetings: meetings, calendar: paris)
}

struct TodayPlannerTests {
    @Test func proposalFromTemplate() {
        let s = state(template: [tmpl(540, 720), tmpl(600, 660, weekday: 4)])
        #expect(s.plan.blocks.count == 1)
        #expect(s.plan.blocks[0].start == at("09:00"))
        #expect(!s.isAccepted)
    }

    @Test func acceptedWinsOverTemplate() {
        let a = accepted("09:00", "10:00")
        let s = state(accepted: a, template: [tmpl(840, 900)])
        #expect(s.plan.blocks == a.plan.blocks)
        #expect(s.isAccepted)
    }

    @Test func meetingsReshapeAcceptedPlan() {
        let a = accepted("09:00", "12:00")
        let meeting = DateInterval(start: at("10:00"), end: at("10:30"))
        let withMeeting = state(accepted: a, meetings: [meeting])
        let without = state(accepted: a)
        #expect(withMeeting.fitted[0].parts.count == 2)
        #expect(without.fitted[0].parts.count == 1)
        #expect(withMeeting.accepted == without.accepted)
    }

    @Test func autoAccept() {
        let s = state(template: [tmpl(540, 600)])
        #expect(!TodayPlanner.autoAcceptDue(s, now: at("08:59")))
        #expect(TodayPlanner.autoAcceptDue(s, now: at("09:00")))
        #expect(TodayPlanner.autoAcceptDue(s, now: at("15:00")))
        #expect(!TodayPlanner.autoAcceptDue(state(accepted: accepted("09:00", "10:00")), now: at("15:00")))
        #expect(!TodayPlanner.autoAcceptDue(state(), now: at("15:00")))

        let dropped = state(
            template: [tmpl(540, 600), tmpl(660, 720)],
            meetings: [DateInterval(start: at("08:50"), end: at("10:10"))]
        )
        #expect(dropped.firstStart == at("11:00"))
    }

    @Test func morningCheckPolicy() {
        let today = LocalDay.nov18
        let yesterday = LocalDay(year: 2026, month: 11, day: 17)
        #expect(MorningCheckPolicy.shouldPresentOnWindowShow(today: today, lastShown: nil, isAccepted: false))
        #expect(!MorningCheckPolicy.shouldPresentOnWindowShow(today: today, lastShown: today, isAccepted: false))
        #expect(!MorningCheckPolicy.shouldPresentOnWindowShow(today: today, lastShown: nil, isAccepted: true))
        #expect(MorningCheckPolicy.shouldPresentOnWindowShow(today: today, lastShown: yesterday, isAccepted: false))
    }

    @Test func reviewSummary() {
        func day(_ d: Int) -> LocalDay { LocalDay(year: 2026, month: 11, day: d) }
        let history = [day(17): true, day(16): false, day(12): true, day(10): true, day(18): true]
        let summary = ReviewSummary.lastSevenDays(before: .nov18, history: history, ignored: [:], calendar: paris)
        #expect(summary?.reviewed == 2)
        #expect(summary?.days == 7)
        #expect(ReviewSummary.lastSevenDays(before: .nov18, history: [:], ignored: [:], calendar: paris) == nil)
    }

    @Test func reviewSummaryWithIgnored() {
        func day(_ d: Int) -> LocalDay { LocalDay(year: 2026, month: 11, day: d) }
        let history = [day(17): true, day(16): false]
        let both = ReviewSummary.lastSevenDays(
            before: .nov18, history: history, ignored: [day(17): 2, day(12): 1], calendar: paris)
        #expect(both?.reviewed == 1)
        #expect(both?.days == 7)
        #expect(both?.ignored == 3)
        let onlyIgnored = ReviewSummary.lastSevenDays(
            before: .nov18, history: [:], ignored: [day(15): 1], calendar: paris)
        #expect(onlyIgnored?.reviewed == 0)
        #expect(onlyIgnored?.ignored == 1)
    }
}
