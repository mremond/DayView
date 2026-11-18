import DayPlanKit
import Foundation
import SwiftData
import Testing
@testable import DayPlanStore

@MainActor
struct SignalsStoreTests {
    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Paris")!
        return calendar
    }

    private let area = Area(id: AreaID(), name: "Writing", colorIndex: 3, order: 0)
    private let start = Date(timeIntervalSince1970: 1_800_000_000)

    private func makeStore() throws -> (PlanStore, String) {
        let name = UUID().uuidString
        let container = try PlanStore.makeContainer(inMemory: true)
        return (PlanStore(container: container, defaults: UserDefaults(suiteName: name)!), name)
    }

    private func day(_ offset: Int) -> LocalDay {
        LocalDay(containing: start.addingTimeInterval(Double(offset) * 86400), calendar: calendar)
    }

    private func focus(at date: Date) -> FocusRecord {
        let session = FocusSession(
            id: UUID(), areaID: area.id, intention: "", origin: .manual, start: date, end: date.addingTimeInterval(1800)
        )
        return FocusRecord(session: session, actualEnd: date.addingTimeInterval(1500), focused: 1400, reason: .completed)
    }

    @Test func countsIgnoredCuesPerDayInclusive() throws {
        let (store, suite) = try makeStore()
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        for offset in [0, 0, 1, 3] {
            try store.saveIgnoredCue(
                areaID: area.id, at: start.addingTimeInterval(Double(offset) * 86400), calendar: calendar
            )
        }
        #expect(store.ignoredCues(from: day(0), through: day(1)) == [day(0): 2, day(1): 1])
        #expect(store.ignoredCues(from: day(1), through: day(3)) == [day(1): 1, day(3): 1])
        #expect(store.ignoredCues(from: day(4), through: day(5)).isEmpty)
    }

    @Test func acceptedPlansRange() throws {
        let (store, suite) = try makeStore()
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        for offset in [0, 1, 4] {
            try store.saveAccepted(DayPlan(day: day(offset), blocks: []), reviewed: offset == 1, at: start)
        }
        let plans = store.acceptedPlans(from: day(0), through: day(1))
        #expect(plans.count == 2)
        #expect(plans[day(0)]?.reviewed == false)
        #expect(plans[day(1)]?.reviewed == true)
        #expect(plans[day(4)] == nil)
    }

    @Test func closedFocusRange() throws {
        let (store, suite) = try makeStore()
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        let later = focus(at: start.addingTimeInterval(86400 + 3600))
        let earlier = focus(at: start)
        let outside = focus(at: start.addingTimeInterval(86400 * 4))
        for item in [later, earlier, outside] {
            try store.saveClosedFocus(item, outcome: .completed, intention: "", area: area, calendar: calendar)
        }
        let result = store.closedFocus(from: day(0), through: day(1))
        #expect(result.map(\.id) == [earlier.session.id, later.session.id])
        #expect(result.map(\.day) == [day(0), day(1)])
    }

    @Test func migratesStage4Store() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("DayPlan.store")
        let name = UUID().uuidString
        defer { UserDefaults().removePersistentDomain(forName: name) }
        let planDay = day(0)
        let plan = DayPlan(day: planDay, blocks: [])
        let saved = focus(at: start)
        do {
            let schema = Schema(versionedSchema: DayPlanSchemaV3.self)
            let configuration = ModelConfiguration(schema: schema, url: url, cloudKitDatabase: .none)
            let old = try ModelContainer(for: schema, configurations: [configuration])
            let areaRecord = AreaRecord()
            areaRecord.apply(area)
            old.mainContext.insert(areaRecord)
            let accepted = AcceptedPlanRecord()
            accepted.dayKey = planDay.isoKey
            accepted.planData = try JSONEncoder().encode(plan)
            old.mainContext.insert(accepted)
            let entry = FocusRecordEntry()
            entry.id = saved.session.id
            entry.dayKey = planDay.isoKey
            entry.areaID = area.id.raw
            entry.start = saved.session.start
            entry.end = saved.actualEnd
            entry.outcome = ClosureOutcome.completed.rawValue
            old.mainContext.insert(entry)
            try old.mainContext.save()
        }
        let store = PlanStore(
            container: try PlanStore.makeContainer(url: url), defaults: UserDefaults(suiteName: name)!
        )
        #expect(store.areas() == [area])
        #expect(store.acceptedPlan(for: planDay)?.plan == plan)
        #expect(store.closedFocus(on: planDay).count == 1)
        try store.saveIgnoredCue(areaID: area.id, at: start, calendar: calendar)
        #expect(store.ignoredCues(from: planDay, through: planDay) == [planDay: 1])
    }
}
