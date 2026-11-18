import Testing
@testable import DayPlanKit

struct LocalDayKeyTests {
    @Test func roundTrip() {
        let day = LocalDay(year: 2026, month: 3, day: 9)
        #expect(day.isoKey == "2026-03-09")
        #expect(LocalDay(isoKey: day.isoKey) == day)
    }

    @Test func padsYear() {
        #expect(LocalDay(year: 987, month: 11, day: 18).isoKey == "0987-11-18")
    }

    @Test(arguments: ["", "2026-03", "2026-3-9", "2026-13-01", "2026-00-10", "2026-03-32", "abcd-ef-gh", "2026-03-09-01"])
    func invalidKeys(_ key: String) {
        #expect(LocalDay(isoKey: key) == nil)
    }
}
