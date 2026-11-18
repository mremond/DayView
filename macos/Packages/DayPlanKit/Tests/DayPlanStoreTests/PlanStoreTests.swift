import Foundation
import SwiftData
import Testing
@testable import DayPlanStore
import DayPlanKit

@MainActor
struct PlanStoreTests {
    private func makeDefaults() -> (UserDefaults, String) {
        let name = UUID().uuidString
        return (UserDefaults(suiteName: name)!, name)
    }

    private func makeStore() throws -> (PlanStore, ModelContainer, UserDefaults, String) {
        let container = try PlanStore.makeContainer(inMemory: true)
        let (defaults, name) = makeDefaults()
        return (PlanStore(container: container, defaults: defaults), container, defaults, name)
    }

    private func area(_ name: String, order: Int = 0) -> Area {
        Area(id: AreaID(), name: name, colorIndex: order, order: order)
    }

    private func block(_ weekday: Int, _ start: Int, _ end: Int, _ area: Area) -> TemplateBlock {
        TemplateBlock(id: BlockID(), weekdayISO: weekday, startMinute: start, endMinute: end, areaID: area.id, intention: "")
    }

    @Test func fileContainerPersistsAcrossOpens() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("DayPlan.store")
        let (defaults, suite) = makeDefaults()
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        let saved = area("Writing")
        do {
            let store = PlanStore(container: try PlanStore.makeContainer(url: url), defaults: defaults)
            try store.save(saved)
        }
        let reopened = PlanStore(container: try PlanStore.makeContainer(url: url), defaults: defaults)
        #expect(reopened.areas() == [saved])
    }

    @Test func areaRoundTrip() throws {
        let (store, _, _, suite) = try makeStore()
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        let deadline = Date(timeIntervalSince1970: 1_800_000_000)
        let original = Area(
            id: AreaID(), name: "Writing", colorIndex: 5, goalTitle: "Finish book", goalDeadline: deadline,
            onGoalApps: [OnGoalApp(bundleID: "a.b", displayName: "AB"), OnGoalApp(bundleID: "c.d", displayName: "CD")],
            order: 0
        )
        try store.save(original)
        #expect(store.areas() == [original])
    }

    @Test func reorder() throws {
        let (store, _, _, suite) = try makeStore()
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        let a = area("A", order: 0), b = area("B", order: 1), c = area("C", order: 2)
        for x in [a, b, c] { try store.save(x) }
        try store.reorderAreas([c.id, a.id, b.id])
        #expect(store.areas().map(\.id) == [c.id, a.id, b.id])
    }

    @Test func seedsOnceEvenAfterDeletion() throws {
        let (store, container, defaults, suite) = try makeStore()
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        let seeds = AreaSeed.defaults(names: ["P", "W", "K", "H"])
        #expect(try store.seedIfNeeded(seeds))
        #expect(store.areas().count == 4)
        for a in store.areas() { try store.deleteArea(a.id) }
        #expect(try store.seedIfNeeded(seeds) == false)
        #expect(store.areas().isEmpty)
        let second = PlanStore(container: container, defaults: defaults)
        #expect(try second.seedIfNeeded(seeds) == false)
    }

    @Test func seedSkippedWhenAreasExist() throws {
        let (store, _, defaults, suite) = try makeStore()
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        try store.save(area("Existing"))
        #expect(try store.seedIfNeeded(AreaSeed.defaults(names: ["P", "W", "K", "H"])) == false)
        #expect(defaults.bool(forKey: "dayplan.areasSeeded"))
        #expect(store.areas().count == 1)
    }

    @Test func deleteAreaCascadesBlocks() throws {
        let (store, container, _, suite) = try makeStore()
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        let w = area("W", order: 0), h = area("H", order: 1)
        try store.save(w)
        try store.save(h)
        let bw = block(1, 540, 720, w), bh = block(1, 1080, 1140, h)
        try store.replaceDay(1, with: [bw, bh])
        #expect(store.blockCount(for: w.id) == 1)
        try store.deleteArea(w.id)
        #expect(store.template() == [bh])
        let records = try container.mainContext.fetch(FetchDescriptor<TemplateBlockRecord>())
        #expect(records.count == 1)
        #expect(records.allSatisfy { $0.area != nil })
    }

    @Test func replaceDayRejectsInvalid() throws {
        let (store, _, _, suite) = try makeStore()
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        let w = area("W")
        try store.save(w)
        let good = [block(1, 540, 600, w)]
        try store.replaceDay(1, with: good)
        let before = store.template()
        #expect(throws: PlanStoreError.invalidDay) {
            try store.replaceDay(1, with: [block(1, 540, 620, w), block(1, 600, 660, w)])
        }
        #expect(store.template() == before)
        #expect(throws: PlanStoreError.invalidDay) {
            try store.replaceDay(1, with: [block(2, 540, 600, w)])
        }
        #expect(store.template() == before)
    }

    @Test func replaceTemplateAfterCopy() throws {
        let (store, _, _, suite) = try makeStore()
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        let w = area("W")
        try store.save(w)
        try store.replaceDay(1, with: [block(1, 540, 600, w), block(1, 700, 760, w)])
        try store.replaceTemplate(TemplateRules.copyingDay(1, to: [3], in: store.template()))
        let t = store.template()
        #expect(t.filter { $0.weekdayISO == 3 }.count == 2)
        #expect(t.filter { $0.weekdayISO == 1 }.count == 2)
    }

    @Test func capQuestionFlagPersists() throws {
        let (store, container, defaults, suite) = try makeStore()
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        #expect(store.capQuestionAsked == false)
        store.capQuestionAsked = true
        #expect(PlanStore(container: container, defaults: defaults).capQuestionAsked)
    }
}
