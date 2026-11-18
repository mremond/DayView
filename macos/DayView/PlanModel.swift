import Foundation
import Combine
import DayPlanKit
import DayPlanStore

/// Observable bridge between SwiftUI and the SwiftData-backed `PlanStore`. Every mutator
/// reloads the published state; store errors are logged and never crash the app.
@MainActor
final class PlanModel: ObservableObject {
    @Published private(set) var areas: [Area] = []
    @Published private(set) var template: [TemplateBlock] = []

    /// False when the on-disk store could not be opened and changes only live in memory.
    let isPersistent: Bool
    private let store: PlanStore

    init(store: PlanStore, isPersistent: Bool = true) {
        self.store = store
        self.isPersistent = isPersistent
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
            let container = try PlanStore.makeContainer(url: try storeURL())
            return PlanModel(store: PlanStore(container: container, defaults: .standard))
        } catch {
            NSLog("DayView: opening the plan store failed, using memory: \(error)")
            guard let container = try? PlanStore.makeContainer(inMemory: true) else {
                fatalError("DayView: cannot create an in-memory plan store")
            }
            // A throwaway suite: the seeded / cap-asked flags must not outlive data lost at quit.
            let volatile = UserDefaults(suiteName: "dayplan.volatile.\(UUID().uuidString)") ?? UserDefaults()
            return PlanModel(store: PlanStore(container: container, defaults: volatile), isPersistent: false)
        }
    }

    /// `<Application Support>/<bundle id>/DayPlan.store`, so Debug and Release builds (whose
    /// seeded / cap-asked flags are per-bundle) never share one store.
    private static func storeURL() throws -> URL {
        let fm = FileManager.default
        let support = try fm.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                 appropriateFor: nil, create: true)
        let folder = support.appendingPathComponent(Bundle.main.bundleIdentifier ?? "fr.dayview.app", isDirectory: true)
        try fm.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder.appendingPathComponent("DayPlan.store")
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

    func acceptedPlan(for day: LocalDay) -> AcceptedPlan? {
        store.acceptedPlan(for: day)
    }

    /// Returns false when the store rejects the write.
    @discardableResult
    func saveAccepted(_ plan: DayPlan, reviewed: Bool, at date: Date) -> Bool {
        attempt("save accepted plan") { try store.saveAccepted(plan, reviewed: reviewed, at: date) }
    }

    func reviewHistory(from: LocalDay, through: LocalDay) -> [LocalDay: Bool] {
        store.reviewHistory(from: from, through: through)
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
        let newAreas = store.areas()
        let newTemplate = store.template()
        if newAreas != areas { areas = newAreas }
        if newTemplate != template { template = newTemplate }
    }
}
