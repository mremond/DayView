import Foundation
import Testing
@testable import DayPlanKit

@Suite struct AreaTests {
    @Test func seedsAreFourInOrder() {
        let names = ["Personal life", "Writing", "Work", "Health"]
        let areas = AreaSeed.defaults(names: names)
        #expect(areas.map(\.name) == names)
        #expect(areas.map(\.colorIndex) == [0, 1, 2, 3])
        #expect(areas.map(\.order) == [0, 1, 2, 3])
        #expect(areas.allSatisfy { $0.goalTitle == "" && $0.goalDeadline == nil && $0.onGoalApps.isEmpty })
        #expect(Set(areas.map(\.id)).count == 4)
    }

    @Test func hasGoal() {
        var area = Area(id: AreaID(), name: "Writing", colorIndex: 0, order: 0)
        #expect(!area.hasGoal)
        area.goalTitle = "Finish the book"
        #expect(area.hasGoal)
        area.goalTitle = ""
        area.goalDeadline = Date(timeIntervalSince1970: 0)
        #expect(area.hasGoal)
    }

    @Test func paletteNormalizes() {
        #expect(AreaPalette.normalized(9) == 1)
        #expect(AreaPalette.normalized(-1) == 7)
    }
}
