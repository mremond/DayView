import Foundation
import Testing
@testable import DayPlanKit

struct GoalDeadlineTests {
    @Test func daysLeft() {
        let now = at("10:00")
        #expect(GoalDeadline.daysLeft(from: now, to: at("08:00", on: LocalDay(year: 2026, month: 11, day: 20)), calendar: paris) == 2)
        #expect(GoalDeadline.daysLeft(from: now, to: at("23:00"), calendar: paris) == 0)
        #expect(GoalDeadline.daysLeft(from: now, to: at("12:00", on: LocalDay(year: 2026, month: 11, day: 17)), calendar: paris) == -1)
        let before = at("12:00", on: LocalDay(year: 2026, month: 3, day: 28))
        let after = at("12:00", on: LocalDay(year: 2026, month: 3, day: 30))
        #expect(GoalDeadline.daysLeft(from: before, to: after, calendar: paris) == 2)
    }
}
