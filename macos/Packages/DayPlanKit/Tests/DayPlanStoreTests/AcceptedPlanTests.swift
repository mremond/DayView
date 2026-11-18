import DayPlanKit
import Foundation
import SwiftData
import Testing
@testable import DayPlanStore

@MainActor
struct AcceptedPlanTests {
    private func makeStore() throws -> (PlanStore, String, ModelContainer) {
        let name = UUID().uuidString
        let container = try PlanStore.makeContainer(inMemory: true)
        let store = PlanStore(container: container, defaults: UserDefaults(suiteName: name)!)
        return (store, name, container)
    }

    private func plan(_ day: LocalDay, blocks count: Int) -> DayPlan {
        let areaID = AreaID()
        let blocks = (0..<count).map { index in
            let start = now.addingTimeInterval(Double(index) * 7200)
            return PlannedBlock(
                id: BlockID(), start: start, end: start.addingTimeInterval(3600),
                areaID: areaID, intention: "Block \(index)"
            )
        }
        return DayPlan(day: day, blocks: blocks)
    }

    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    @Test func acceptedRoundTrip() throws {
        let (store, suite, _) = try makeStore()
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        let saved = plan(LocalDay(year: 2026, month: 11, day: 18), blocks: 2)
        try store.saveAccepted(saved, reviewed: true, at: now)
        #expect(store.acceptedPlan(for: saved.day) == AcceptedPlan(plan: saved, reviewed: true))
    }

    @Test func saveAcceptedUpserts() throws {
        let (store, suite, container) = try makeStore()
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        let day = LocalDay(year: 2026, month: 11, day: 18)
        try store.saveAccepted(plan(day, blocks: 2), reviewed: true, at: now)
        let second = plan(day, blocks: 1)
        try store.saveAccepted(second, reviewed: false, at: now.addingTimeInterval(60))
        #expect(store.acceptedPlan(for: day) == AcceptedPlan(plan: second, reviewed: false))
        let records = try container.mainContext.fetch(FetchDescriptor<AcceptedPlanRecord>())
        #expect(records.count == 1)
    }

    @Test func otherDayUntouched() throws {
        let (store, suite, _) = try makeStore()
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        let yesterday = plan(LocalDay(year: 2026, month: 11, day: 17), blocks: 1)
        let today = plan(LocalDay(year: 2026, month: 11, day: 18), blocks: 2)
        try store.saveAccepted(yesterday, reviewed: true, at: now)
        try store.saveAccepted(today, reviewed: false, at: now)
        #expect(store.acceptedPlan(for: yesterday.day) == AcceptedPlan(plan: yesterday, reviewed: true))
        #expect(store.acceptedPlan(for: today.day) == AcceptedPlan(plan: today, reviewed: false))
        #expect(store.acceptedPlan(for: LocalDay(year: 2026, month: 11, day: 19)) == nil)
    }

    @Test func reviewHistoryRange() throws {
        let (store, suite, _) = try makeStore()
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        for (day, reviewed) in [(10, true), (15, false), (18, true)] {
            try store.saveAccepted(plan(LocalDay(year: 2026, month: 11, day: day), blocks: 1), reviewed: reviewed, at: now)
        }
        let history = store.reviewHistory(
            from: LocalDay(year: 2026, month: 11, day: 11), through: LocalDay(year: 2026, month: 11, day: 17)
        )
        #expect(history == [LocalDay(year: 2026, month: 11, day: 15): false])
    }

    @Test func migratesStage2Store() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("DayPlan.store")
        let name = UUID().uuidString
        defer { UserDefaults().removePersistentDomain(forName: name) }
        let defaults = UserDefaults(suiteName: name)!
        let saved = Area(id: AreaID(), name: "Writing", colorIndex: 2, order: 0)
        do {
            let schema = Schema([AreaRecord.self, TemplateBlockRecord.self])
            let configuration = ModelConfiguration(schema: schema, url: url, cloudKitDatabase: .none)
            let oldContainer = try ModelContainer(for: schema, configurations: [configuration])
            let record = AreaRecord()
            record.apply(saved)
            oldContainer.mainContext.insert(record)
            try oldContainer.mainContext.save()
        }
        let store = PlanStore(container: try PlanStore.makeContainer(url: url), defaults: defaults)
        #expect(store.areas() == [saved])
        let day = LocalDay(year: 2026, month: 11, day: 18)
        try store.saveAccepted(plan(day, blocks: 1), reviewed: true, at: now)
        #expect(store.acceptedPlan(for: day) != nil)
    }
}
