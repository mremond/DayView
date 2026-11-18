import DayPlanKit
import Foundation
import SwiftData
import Testing
@testable import DayPlanStore

@MainActor
struct FocusMetricsTests {
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

    private func stretch(_ from: Double, _ length: Double) -> DateInterval {
        DateInterval(start: start.addingTimeInterval(from), duration: length)
    }

    private func metrics(
        offset: Int = 0, id: UUID = UUID(), deep: TimeInterval? = 600, stretches: [DateInterval] = []
    ) -> FocusMetrics {
        FocusMetrics(
            sessionID: id, areaID: area.id, day: day(offset), engaged: 1200, deep: deep, deepStretches: stretches
        )
    }

    @Test func roundTripsWithStretches() throws {
        let (store, suite) = try makeStore()
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        let saved = metrics(stretches: [stretch(600, 300), stretch(0, 200)])
        try store.saveFocusMetrics(saved)
        let loaded = store.focusMetrics(from: day(0), through: day(0))[saved.sessionID]
        #expect(loaded?.engaged == 1200)
        #expect(loaded?.deep == 600)
        #expect(loaded?.day == day(0))
        #expect(loaded?.areaID == area.id)
        #expect(loaded?.deepStretches == [stretch(0, 200), stretch(600, 300)])
    }

    @Test func upsertReplacesStretches() throws {
        let (store, suite) = try makeStore()
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        let id = UUID()
        try store.saveFocusMetrics(metrics(id: id, stretches: [stretch(0, 200), stretch(300, 200)]))
        var second = metrics(id: id, deep: 150, stretches: [stretch(500, 150)])
        second.engaged = 900
        try store.saveFocusMetrics(second)
        let result = store.focusMetrics(from: day(0), through: day(0))
        #expect(result.count == 1)
        #expect(result[id]?.deepStretches == [stretch(500, 150)])
        #expect(result[id]?.engaged == 900)
        #expect(result[id]?.deep == 150)
    }

    @Test func rangeIsInclusive() throws {
        let (store, suite) = try makeStore()
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        let items = [0, 1, 4].map { metrics(offset: $0) }
        for item in items { try store.saveFocusMetrics(item) }
        let result = store.focusMetrics(from: day(0), through: day(1))
        #expect(Set(result.keys) == [items[0].sessionID, items[1].sessionID])
    }

    @Test func unmeasuredDeepRoundTripsAsNil() throws {
        let (store, suite) = try makeStore()
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        let saved = metrics(deep: nil)
        try store.saveFocusMetrics(saved)
        let loaded = store.focusMetrics(from: day(0), through: day(0))[saved.sessionID]
        #expect(loaded?.deep == nil)
        #expect(loaded?.deepStretches == [])
        #expect(loaded == saved)
    }

    @Test func migratesStage5Store() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("DayPlan.store")
        let name = UUID().uuidString
        defer { UserDefaults().removePersistentDomain(forName: name) }
        let planDay = day(0)
        let plan = DayPlan(day: planDay, blocks: [])
        do {
            let schema = Schema(versionedSchema: DayPlanSchemaV4.self)
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
            entry.id = UUID()
            entry.dayKey = planDay.isoKey
            entry.areaID = area.id.raw
            entry.start = start
            entry.end = start.addingTimeInterval(1500)
            entry.outcome = ClosureOutcome.completed.rawValue
            old.mainContext.insert(entry)
            let cue = IgnoredCueEntry()
            cue.dayKey = planDay.isoKey
            cue.at = start
            cue.areaID = area.id.raw
            old.mainContext.insert(cue)
            try old.mainContext.save()
        }
        let store = PlanStore(
            container: try PlanStore.makeContainer(url: url), defaults: UserDefaults(suiteName: name)!
        )
        #expect(store.areas() == [area])
        #expect(store.acceptedPlan(for: planDay)?.plan == plan)
        #expect(store.closedFocus(on: planDay).count == 1)
        #expect(store.ignoredCues(from: planDay, through: planDay) == [planDay: 1])
        let saved = metrics(stretches: [stretch(0, 300)])
        try store.saveFocusMetrics(saved)
        #expect(store.focusMetrics(from: planDay, through: planDay) == [saved.sessionID: saved])
    }

    @Test func mergedSaveKeepsStoredStretches() throws {
        let (store, suite) = try makeStore()
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        let id = UUID()
        try store.saveFocusMetrics(metrics(id: id, deep: 200, stretches: [stretch(0, 200)]))
        var second = metrics(id: id, deep: 150, stretches: [stretch(900, 150)])
        second.engaged = 1500
        let stored = store.focusMetrics(from: day(0), through: day(0))[id]
        try store.saveFocusMetrics(second.merged(withStored: stored))
        let loaded = store.focusMetrics(from: day(0), through: day(0))[id]
        #expect(loaded?.deepStretches == [stretch(0, 200), stretch(900, 150)])
        #expect(loaded?.deep == 350)
        #expect(loaded?.engaged == 1500)
    }

    @Test func mergeKeepsStoredDeepWhenNotMeasuredNow() {
        let id = UUID()
        let stored = metrics(id: id, deep: 200, stretches: [stretch(0, 200)])
        let merged = metrics(id: id, deep: nil).merged(withStored: stored)
        #expect(merged.deep == 200)
        #expect(merged.deepStretches == [stretch(0, 200)])
    }

    @Test func mergeDropsDuplicateStretchesAndIgnoresOtherSessions() {
        let id = UUID()
        let stored = metrics(id: id, deep: 200, stretches: [stretch(0, 200)])
        let again = metrics(id: id, deep: 200, stretches: [stretch(0, 200)])
        #expect(again.merged(withStored: stored).deepStretches == [stretch(0, 200)])
        #expect(again.merged(withStored: stored).deep == 200)
        let other = metrics(deep: nil)
        #expect(other.merged(withStored: stored) == other)
        #expect(metrics(id: id, deep: nil).merged(withStored: nil).deep == nil)
    }
}
