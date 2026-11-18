import Foundation
import SwiftUI
import DayPlanKit
import DayPlanStore

/// Observable bridge between SwiftUI and the SwiftData-backed `PlanStore`. Every mutator
/// reloads the published state; store errors are logged and never crash the app.
@MainActor
final class PlanModel: ObservableObject {
    @Published private(set) var areas: [Area] = []
    @Published private(set) var template: [TemplateBlock] = []

    private let store: PlanStore

    init(store: PlanStore) {
        self.store = store
        do {
            _ = try store.seedIfNeeded(AreaSeed.defaults(names: [
                L("Personal life"), L("Writing"), L("Work"), L("Health")
            ]))
        } catch {
            NSLog("DayView: seeding areas failed: \(error)")
        }
        reload()
    }

    static func live() -> PlanModel {
        do {
            let container = try PlanStore.makeContainer(inMemory: false)
            return PlanModel(store: PlanStore(container: container, defaults: .standard))
        } catch {
            NSLog("DayView: opening the plan store failed, using memory: \(error)")
            // An in-memory container is not expected to fail; there is nothing left to fall back on.
            // swiftlint:disable:next force_try
            let container = try! PlanStore.makeContainer(inMemory: true)
            return PlanModel(store: PlanStore(container: container, defaults: .standard))
        }
    }

    var capQuestionAsked: Bool {
        get { store.capQuestionAsked }
        set { store.capQuestionAsked = newValue }
    }

    func save(_ area: Area) {
        attempt("save area") { try store.save(area) }
    }

    func deleteArea(_ id: AreaID) {
        attempt("delete area") { try store.deleteArea(id) }
    }

    func moveAreas(from source: IndexSet, to destination: Int) {
        var ids = areas.map(\.id)
        ids.move(fromOffsets: source, toOffset: destination)
        attempt("reorder areas") { try store.reorderAreas(ids) }
    }

    func blockCount(for id: AreaID) -> Int {
        store.blockCount(for: id)
    }

    /// Returns false when the store rejects the day (overlap, bad range, bad weekday).
    func replaceDay(_ weekday: Int, with blocks: [TemplateBlock]) -> Bool {
        let ok = attempt("replace day") { try store.replaceDay(weekday, with: blocks) }
        return ok
    }

    func copyDay(_ source: Int, to targets: Set<Int>) {
        let updated = TemplateRules.copyingDay(source, to: targets, in: store.template())
        attempt("copy day") { try store.replaceTemplate(updated) }
    }

    func addArea() {
        let area = Area(
            id: AreaID(),
            name: L("New area"),
            colorIndex: AreaPalette.normalized(areas.count),
            order: (areas.map(\.order).max() ?? -1) + 1
        )
        attempt("add area") { try store.save(area) }
    }

    @discardableResult
    private func attempt(_ what: String, _ work: () throws -> Void) -> Bool {
        var ok = true
        do { try work() } catch {
            ok = false
            NSLog("DayView: \(what) failed: \(error)")
        }
        reload()
        return ok
    }

    private func reload() {
        areas = store.areas()
        template = store.template()
    }
}
