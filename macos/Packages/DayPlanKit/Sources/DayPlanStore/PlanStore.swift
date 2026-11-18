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

    private static var schema: Schema { Schema(versionedSchema: DayPlanSchemaV5.self) }

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

    /// The accepted plan of each day in `from...through` that has one (latest `acceptedAt` wins).
    public func acceptedPlans(from: LocalDay, through: LocalDay) -> [LocalDay: AcceptedPlan] {
        let lower = from.isoKey, upper = through.isoKey
        let descriptor = FetchDescriptor<AcceptedPlanRecord>(
            predicate: #Predicate { $0.dayKey >= lower && $0.dayKey <= upper }
        )
        var latest: [String: AcceptedPlanRecord] = [:]
        for record in (try? context.fetch(descriptor)) ?? [] {
            if let kept = latest[record.dayKey], kept.acceptedAt >= record.acceptedAt { continue }
            latest[record.dayKey] = record
        }
        var result: [LocalDay: AcceptedPlan] = [:]
        for (key, record) in latest {
            guard let day = LocalDay(isoKey: key),
                  let plan = try? JSONDecoder().decode(DayPlan.self, from: record.planData)
            else { continue }
            result[day] = AcceptedPlan(plan: plan, reviewed: record.reviewed)
        }
        return result
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

    // MARK: Closed Focus

    /// Stores a closed Focus, replacing any earlier entry with the same session id.
    /// `intention` is the user-edited intention at close time.
    public func saveClosedFocus(
        _ record: FocusRecord, outcome: ClosureOutcome, intention: String, area: Area, calendar: Calendar
    ) throws {
        let day = LocalDay(containing: record.session.start, calendar: calendar)
        let id = record.session.id
        let existing = closedRecords(id: id)
        let entry = existing.first ?? FocusRecordEntry()
        if existing.isEmpty { context.insert(entry) }
        for duplicate in existing.dropFirst() { context.delete(duplicate) }
        entry.id = id
        entry.dayKey = day.isoKey
        entry.areaID = area.id.raw
        entry.areaName = area.name
        entry.colorIndex = area.colorIndex
        entry.intention = intention
        entry.start = record.session.start
        entry.end = record.actualEnd
        entry.focusedSeconds = record.focused
        if case .planned(let block) = record.session.origin {
            entry.isPlanned = true
            entry.blockID = block.raw
        } else {
            entry.isPlanned = false
            entry.blockID = nil
        }
        entry.reason = Self.reasonName(record.reason)
        entry.outcome = outcome.rawValue
        try commit()
    }

    /// The closed Focus records that started on `day`, by start time.
    public func closedFocus(on day: LocalDay) -> [ClosedFocus] {
        let key = day.isoKey
        let descriptor = FetchDescriptor<FocusRecordEntry>(
            predicate: #Predicate { $0.dayKey == key }, sortBy: [SortDescriptor(\.start)]
        )
        let entries = (try? context.fetch(descriptor)) ?? []
        return entries.compactMap { $0.value(day: day) }
    }

    /// The closed Focus records that started in `from...through`, by start time.
    public func closedFocus(from: LocalDay, through: LocalDay) -> [ClosedFocus] {
        let lower = from.isoKey, upper = through.isoKey
        let descriptor = FetchDescriptor<FocusRecordEntry>(
            predicate: #Predicate { $0.dayKey >= lower && $0.dayKey <= upper },
            sortBy: [SortDescriptor(\.start)]
        )
        let entries = (try? context.fetch(descriptor)) ?? []
        return entries.compactMap { entry in
            LocalDay(isoKey: entry.dayKey).flatMap { entry.value(day: $0) }
        }
    }

    // MARK: Ignored cues

    /// Records a cue the user ignored, on the local day of `at`.
    public func saveIgnoredCue(areaID: AreaID, at: Date, calendar: Calendar) throws {
        let entry = IgnoredCueEntry()
        entry.dayKey = LocalDay(containing: at, calendar: calendar).isoKey
        entry.at = at
        entry.areaID = areaID.raw
        context.insert(entry)
        try commit()
    }

    /// The number of ignored cues for each day in `from...through` that has any.
    public func ignoredCues(from: LocalDay, through: LocalDay) -> [LocalDay: Int] {
        let lower = from.isoKey, upper = through.isoKey
        let descriptor = FetchDescriptor<IgnoredCueEntry>(
            predicate: #Predicate { $0.dayKey >= lower && $0.dayKey <= upper }
        )
        var result: [LocalDay: Int] = [:]
        for entry in (try? context.fetch(descriptor)) ?? [] {
            if let day = LocalDay(isoKey: entry.dayKey) { result[day, default: 0] += 1 }
        }
        return result
    }

    // MARK: Focus metrics

    /// Stores the metrics of a Focus session, replacing any earlier ones and that session's stretches.
    public func saveFocusMetrics(_ metrics: FocusMetrics) throws {
        let id = metrics.sessionID
        let key = metrics.day.isoKey
        let existing = (try? context.fetch(
            FetchDescriptor<FocusMetricsEntry>(predicate: #Predicate { $0.sessionID == id })
        )) ?? []
        let entry = existing.first ?? FocusMetricsEntry()
        if existing.isEmpty { context.insert(entry) }
        for duplicate in existing.dropFirst() { context.delete(duplicate) }
        entry.sessionID = id
        entry.dayKey = key
        entry.areaID = metrics.areaID.raw
        entry.engagedSeconds = metrics.engaged
        entry.deepMeasured = metrics.deep != nil
        entry.deepSeconds = metrics.deep ?? 0
        let stale = (try? context.fetch(
            FetchDescriptor<FocusPresenceEntry>(predicate: #Predicate { $0.sessionID == id })
        )) ?? []
        for old in stale { context.delete(old) }
        for stretch in metrics.deepStretches {
            let presence = FocusPresenceEntry()
            presence.sessionID = id
            presence.dayKey = key
            presence.start = stretch.start
            presence.end = stretch.end
            context.insert(presence)
        }
        try commit()
    }

    /// The metrics of the sessions that started in `from...through`, by session id.
    public func focusMetrics(from: LocalDay, through: LocalDay) -> [UUID: FocusMetrics] {
        let lower = from.isoKey, upper = through.isoKey
        let entries = (try? context.fetch(FetchDescriptor<FocusMetricsEntry>(
            predicate: #Predicate { $0.dayKey >= lower && $0.dayKey <= upper }
        ))) ?? []
        let presences = (try? context.fetch(FetchDescriptor<FocusPresenceEntry>(
            predicate: #Predicate { $0.dayKey >= lower && $0.dayKey <= upper }
        ))) ?? []
        let stretches = Dictionary(grouping: presences, by: \.sessionID)
        var result: [UUID: FocusMetrics] = [:]
        for entry in entries where result[entry.sessionID] == nil {
            guard let day = LocalDay(isoKey: entry.dayKey) else { continue }
            let intervals = (stretches[entry.sessionID] ?? []).sorted { $0.start < $1.start }
                .map { DateInterval(start: $0.start, end: max($0.start, $0.end)) }
            result[entry.sessionID] = FocusMetrics(
                sessionID: entry.sessionID, areaID: AreaID(entry.areaID), day: day,
                engaged: entry.engagedSeconds, deep: entry.deepMeasured ? entry.deepSeconds : nil,
                deepStretches: intervals
            )
        }
        return result
    }

    private static func reasonName(_ reason: FocusEndReason) -> String {
        switch reason {
        case .completed: "completed"
        case .stopped: "stopped"
        case .tookOver: "tookOver"
        }
    }

    // MARK: Private

    private func closedRecords(id: UUID) -> [FocusRecordEntry] {
        let descriptor = FetchDescriptor<FocusRecordEntry>(predicate: #Predicate { $0.id == id })
        return (try? context.fetch(descriptor)) ?? []
    }

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
