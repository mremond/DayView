import DayPlanKit
import Foundation
import SwiftData
import Testing
@testable import DayPlanStore

@MainActor
struct ClosedFocusTests {
    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Paris")!
        return calendar
    }

    private let area = Area(id: AreaID(), name: "Writing", colorIndex: 3, order: 0)
    private let start = Date(timeIntervalSince1970: 1_800_000_000)

    private func makeStore() throws -> (PlanStore, String, ModelContainer) {
        let name = UUID().uuidString
        let container = try PlanStore.makeContainer(inMemory: true)
        return (PlanStore(container: container, defaults: UserDefaults(suiteName: name)!), name, container)
    }

    private func record(
        id: UUID = UUID(), at start: Date, origin: FocusOrigin = .manual, reason: FocusEndReason = .completed
    ) -> FocusRecord {
        let session = FocusSession(
            id: id, areaID: area.id, intention: "Original", origin: origin,
            start: start, end: start.addingTimeInterval(1800)
        )
        return FocusRecord(session: session, actualEnd: start.addingTimeInterval(1500), focused: 1400, reason: reason)
    }

    @Test func roundTrip() throws {
        let (store, suite, _) = try makeStore()
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        let block = BlockID()
        let saved = record(at: start, origin: .planned(block), reason: .tookOver)
        try store.saveClosedFocus(saved, outcome: .progressed, intention: "Edited", area: area, calendar: calendar)
        let day = LocalDay(containing: start, calendar: calendar)
        #expect(store.closedFocus(on: day) == [
            ClosedFocus(
                id: saved.session.id, day: day, areaID: area.id, areaName: "Writing", colorIndex: 3,
                intention: "Edited", start: start, end: start.addingTimeInterval(1500), focused: 1400,
                isPlanned: true, outcome: .progressed
            )
        ])
    }

    @Test func upsertsBySessionID() throws {
        let (store, suite, container) = try makeStore()
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        let saved = record(at: start)
        try store.saveClosedFocus(saved, outcome: .toResume, intention: "A", area: area, calendar: calendar)
        try store.saveClosedFocus(saved, outcome: .completed, intention: "B", area: area, calendar: calendar)
        let day = LocalDay(containing: start, calendar: calendar)
        let result = store.closedFocus(on: day)
        #expect(result.count == 1)
        #expect(result.first?.outcome == .completed)
        #expect(result.first?.intention == "B")
        #expect(try container.mainContext.fetch(FetchDescriptor<FocusRecordEntry>()).count == 1)
    }

    @Test func filtersByDayAndSortsByStart() throws {
        let (store, suite, _) = try makeStore()
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        let later = record(at: start.addingTimeInterval(7200))
        let earlier = record(at: start)
        let nextDay = record(at: start.addingTimeInterval(86400 * 2))
        for item in [later, earlier, nextDay] {
            try store.saveClosedFocus(item, outcome: .completed, intention: "", area: area, calendar: calendar)
        }
        let day = LocalDay(containing: start, calendar: calendar)
        #expect(store.closedFocus(on: day).map(\.id) == [earlier.session.id, later.session.id])
        #expect(store.closedFocus(on: LocalDay(containing: nextDay.session.start, calendar: calendar)).count == 1)
    }

    @Test func migratesStage3Store() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("DayPlan.store")
        let name = UUID().uuidString
        defer { UserDefaults().removePersistentDomain(forName: name) }
        let day = LocalDay(year: 2026, month: 11, day: 18)
        let plan = DayPlan(day: day, blocks: [])
        do {
            let schema = Schema(versionedSchema: DayPlanSchemaV2.self)
            let configuration = ModelConfiguration(schema: schema, url: url, cloudKitDatabase: .none)
            let old = try ModelContainer(for: schema, configurations: [configuration])
            let areaRecord = AreaRecord()
            areaRecord.apply(area)
            old.mainContext.insert(areaRecord)
            let accepted = AcceptedPlanRecord()
            accepted.dayKey = day.isoKey
            accepted.planData = try JSONEncoder().encode(plan)
            old.mainContext.insert(accepted)
            try old.mainContext.save()
        }
        let store = PlanStore(
            container: try PlanStore.makeContainer(url: url), defaults: UserDefaults(suiteName: name)!
        )
        #expect(store.areas() == [area])
        #expect(store.acceptedPlan(for: day)?.plan == plan)
        let saved = record(at: start)
        try store.saveClosedFocus(saved, outcome: .completed, intention: "x", area: area, calendar: calendar)
        #expect(store.closedFocus(on: LocalDay(containing: start, calendar: calendar)).count == 1)
    }
}
