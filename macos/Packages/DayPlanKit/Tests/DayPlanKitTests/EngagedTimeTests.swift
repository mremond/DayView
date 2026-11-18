import Foundation
import Testing
@testable import DayPlanKit

private func span(_ s: String, _ e: String) -> DateInterval { DateInterval(start: at(s), end: at(e)) }

@Test func engagedSubtractsMergedDetours() {
    let detours = [span("09:50", "10:10"), span("10:30", "10:40"), span("10:35", "10:45")]
    #expect(EngagedTime.compute(focused: 3600, window: span("10:00", "11:00"), detours: detours) == 2100)
}

@Test func engagedNeverNegative() {
    #expect(EngagedTime.compute(focused: 3600, window: span("10:00", "11:00"), detours: [span("09:00", "12:00")]) == 0)
}

@Test func engagedWithoutDetours() {
    #expect(EngagedTime.compute(focused: 1500, window: span("10:00", "10:25"), detours: []) == 1500)
}
