import Foundation
import Testing
@testable import DayPlanKit

@Suite struct CapMigrationTests {
    private let ulysses = OnGoalApp(bundleID: "com.ulyssesapp.mac", displayName: "Ulysses")
    private let scrivener = OnGoalApp(bundleID: "com.literatureandlatte.scrivener3", displayName: "Scrivener")

    @Test func asksOnlyWhenSomethingToMove() {
        func ask(title: String = "", deadline: Date? = nil, apps: [OnGoalApp] = [], asked: Bool = false) -> Bool {
            CapMigration.needsQuestion(goalTitle: title, goalDeadline: deadline, onGoalApps: apps, alreadyAsked: asked)
        }
        #expect(!ask())
        #expect(!ask(title: "  \n "))
        #expect(ask(title: "Book"))
        #expect(ask(deadline: Date()))
        #expect(ask(apps: [ulysses]))
        #expect(!ask(title: "Book", deadline: Date(), apps: [ulysses], asked: true))
    }

    @Test func applyMergesApps() {
        let area = Area(id: AreaID(), name: "Writing", colorIndex: 1, onGoalApps: [ulysses], order: 1)
        let deadline = Date(timeIntervalSince1970: 1_800_000_000)
        let result = CapMigration.applying(
            goalTitle: "  Finish book  ", goalDeadline: deadline, onGoalApps: [ulysses, scrivener], to: area)
        #expect(result.goalTitle == "Finish book")
        #expect(result.goalDeadline == deadline)
        #expect(result.onGoalApps == [ulysses, scrivener])
    }
}
