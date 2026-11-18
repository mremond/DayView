import DayPlanKit
import Foundation
import SwiftData

public enum PlanStoreError: Error {
    case invalidDay
}

/// Local persistence for life areas and the weekly template.
@MainActor
public final class PlanStore {
    private static let seededKey = "dayplan.areasSeeded"
    private static let capQuestionKey = "dayplan.capQuestionAsked"

    private let container: ModelContainer
    private let defaults: UserDefaults
    private var context: ModelContext { container.mainContext }

    private static var schema: Schema { Schema(versionedSchema: DayPlanSchemaV2.self) }

    /// An in-memory container for tests and the fallback when the store cannot be opened.
    /// With `inMemory: false` the store lands in the default (shared) location: prefer `makeContainer(url:)`.
    public static func makeContainer(inMemory: Bool) throws -> ModelContainer {
        let configuration = ModelConfiguration(
            "DayPlan", schema: schema, isStoredInMemoryOnly: inMemory, cloudKitDatabase: .none
        )
        return try ModelContainer(
            for: schema, migrationPlan: DayPlanMigrationPlan.self, configurations: [configuration]
        )
    }

    /// A local-only container persisted at `url` (the parent directory must exist).
    public static func makeContainer(url: URL) throws -> ModelContainer {
        let configuration = ModelConfiguration(schema: schema, url: url, cloudKitDatabase: .none)
        return try ModelContainer(
            for: schema, migrationPlan: DayPlanMigrationPlan.self, configurations: [configuration]
        )
    }

    public init(container: ModelContainer, defaults: UserDefaults) {
        self.container = container
        self.defaults = defaults
    }

    public var capQuestionAsked: Bool {
        get { defaults.bool(forKey: Self.capQuestionKey) }
        set { defaults.set(newValue, forKey: Self.capQuestionKey) }
    }

    // MARK: Areas

    public func areas() -> [Area] {
        areaRecords().map(\.value).sorted { ($0.order, $0.name) < ($1.order, $1.name) }
    }

    public func save(_ area: Area) throws {
        if let record = areaRecord(area.id) {
            record.apply(area)
        } else {
            let record = AreaRecord()
            record.apply(area)
            context.insert(record)
        }
        try commit()
    }

    public func deleteArea(_ id: AreaID) throws {
        guard let record = areaRecord(id) else { return }
        context.delete(record)
        try commit()
    }

    public func reorderAreas(_ ids: [AreaID]) throws {
        let records = areaRecords()
        for (index, id) in ids.enumerated() {
            records.first { $0.id == id.raw }?.order = index
        }
        try commit()
    }

    public func blockCount(for id: AreaID) -> Int {
        areaRecord(id)?.blocks?.count ?? 0
    }

    /// Inserts `seeds` once, only if never seeded and no areas exist. Returns whether it seeded.
    public func seedIfNeeded(_ seeds: [Area]) throws -> Bool {
        guard !defaults.bool(forKey: Self.seededKey) else { return false }
        guard areaRecords().isEmpty else {
            defaults.set(true, forKey: Self.seededKey)
            return false
        }
        for seed in seeds {
            let record = AreaRecord()
            record.apply(seed)
            context.insert(record)
        }
        try commit()
        defaults.set(true, forKey: Self.seededKey)
        return true
    }

    // MARK: Template

    public func template() -> [TemplateBlock] {
        let blocks = blockRecords().compactMap(\.value)
        return blocks.sorted { ($0.weekdayISO, $0.startMinute) < ($1.weekdayISO, $1.startMinute) }
    }

    public func replaceDay(_ weekdayISO: Int, with blocks: [TemplateBlock]) throws {
        guard blocks.allSatisfy({ $0.weekdayISO == weekdayISO }), TemplateRules.isValidDay(blocks) else {
            throw PlanStoreError.invalidDay
        }
        replaceRecords(forDay: weekdayISO, with: blocks)
        try commit()
    }

    public func replaceTemplate(_ blocks: [TemplateBlock]) throws {
        let byDay = Dictionary(grouping: blocks, by: \.weekdayISO)
        guard byDay.values.allSatisfy(TemplateRules.isValidDay), blocks.allSatisfy(TemplateRules.isWellFormed) else {
            throw PlanStoreError.invalidDay
        }
        for weekday in 1...7 {
            replaceRecords(forDay: weekday, with: byDay[weekday] ?? [])
        }
        try commit()
    }

    // MARK: Accepted plans

    /// The accepted plan for `day`; nil when absent or undecodable. The latest `acceptedAt` wins on duplicates.
    public func acceptedPlan(for day: LocalDay) -> AcceptedPlan? {
        guard let record = acceptedRecords(dayKey: day.isoKey).max(by: { $0.acceptedAt < $1.acceptedAt }),
              let plan = try? JSONDecoder().decode(DayPlan.self, from: record.planData)
        else { return nil }
        return AcceptedPlan(plan: plan, reviewed: record.reviewed)
    }

    /// Stores `plan` as the accepted plan for its day, replacing any earlier one.
    public func saveAccepted(_ plan: DayPlan, reviewed: Bool, at date: Date) throws {
        let key = plan.day.isoKey
        let data = try JSONEncoder().encode(plan)
        let existing = acceptedRecords(dayKey: key)
        let record = existing.first ?? AcceptedPlanRecord()
        if existing.isEmpty { context.insert(record) }
        for duplicate in existing.dropFirst() { context.delete(duplicate) }
        record.dayKey = key
        record.reviewed = reviewed
        record.acceptedAt = date
        record.planData = data
        try commit()
    }

    /// Whether the plan was reviewed, for each day in `from...through` that has an accepted plan.
    public func reviewHistory(from: LocalDay, through: LocalDay) -> [LocalDay: Bool] {
        let lower = from.isoKey, upper = through.isoKey
        var result: [LocalDay: Bool] = [:]
        for record in allAcceptedRecords() where record.dayKey >= lower && record.dayKey <= upper {
            if let day = LocalDay(isoKey: record.dayKey) { result[day] = record.reviewed }
        }
        return result
    }

    // MARK: Private

    private func allAcceptedRecords() -> [AcceptedPlanRecord] {
        (try? context.fetch(FetchDescriptor<AcceptedPlanRecord>())) ?? []
    }

    private func acceptedRecords(dayKey: String) -> [AcceptedPlanRecord] {
        let descriptor = FetchDescriptor<AcceptedPlanRecord>(predicate: #Predicate { $0.dayKey == dayKey })
        return (try? context.fetch(descriptor)) ?? []
    }

    /// Saves the context; on failure discards pending changes so they can't leak into a later save.
    private func commit() throws {
        do {
            try context.save()
        } catch {
            context.rollback()
            throw error
        }
    }

    private func replaceRecords(forDay weekday: Int, with blocks: [TemplateBlock]) {
        for record in blockRecords() where record.weekdayISO == weekday {
            context.delete(record)
        }
        let areas = Dictionary(areaRecords().map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        for block in blocks {
            guard let area = areas[block.areaID.raw] else { continue }
            let record = TemplateBlockRecord()
            record.apply(block)
            context.insert(record)
            record.area = area
        }
    }

    private func areaRecords() -> [AreaRecord] {
        (try? context.fetch(FetchDescriptor<AreaRecord>())) ?? []
    }

    private func blockRecords() -> [TemplateBlockRecord] {
        (try? context.fetch(FetchDescriptor<TemplateBlockRecord>())) ?? []
    }

    private func areaRecord(_ id: AreaID) -> AreaRecord? {
        areaRecords().first { $0.id == id.raw }
    }
}
