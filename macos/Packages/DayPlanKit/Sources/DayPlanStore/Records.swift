import DayPlanKit
import Foundation
import SwiftData

/// Persisted life area. All properties are defaulted/optional so the schema stays CloudKit-compatible.
@Model
final class AreaRecord {
    var id: UUID = UUID()
    var name: String = ""
    var colorIndex: Int = 0
    var goalTitle: String = ""
    var goalDeadline: Date?
    /// JSON of `[OnGoalApp]`; empty data means no apps.
    var onGoalAppsData: Data = Data()
    var order: Int = 0
    @Relationship(deleteRule: .cascade, inverse: \TemplateBlockRecord.area)
    var blocks: [TemplateBlockRecord]? = []

    init() {}

    func apply(_ area: Area) {
        id = area.id.raw
        name = area.name
        colorIndex = area.colorIndex
        goalTitle = area.goalTitle
        goalDeadline = area.goalDeadline
        onGoalAppsData = (try? JSONEncoder().encode(area.onGoalApps)) ?? Data()
        order = area.order
    }

    var value: Area {
        let apps = (try? JSONDecoder().decode([OnGoalApp].self, from: onGoalAppsData)) ?? []
        return Area(
            id: AreaID(id), name: name, colorIndex: colorIndex, goalTitle: goalTitle,
            goalDeadline: goalDeadline, onGoalApps: apps, order: order
        )
    }
}

/// Persisted weekly-template block, owned by an area.
@Model
final class TemplateBlockRecord {
    var id: UUID = UUID()
    var weekdayISO: Int = 1
    var startMinute: Int = 0
    var endMinute: Int = 0
    var intention: String = ""
    var area: AreaRecord?

    init() {}

    func apply(_ block: TemplateBlock) {
        id = block.id.raw
        weekdayISO = block.weekdayISO
        startMinute = block.startMinute
        endMinute = block.endMinute
        intention = block.intention
    }

    /// Nil when the block has lost its area.
    var value: TemplateBlock? {
        guard let area else { return nil }
        return TemplateBlock(
            id: BlockID(id), weekdayISO: weekdayISO, startMinute: startMinute,
            endMinute: endMinute, areaID: AreaID(area.id), intention: intention
        )
    }
}

/// Persisted accepted day plan, one per day (`dayKey`). The plan itself is stored unfitted as JSON.
@Model
final class AcceptedPlanRecord {
    /// `"YYYY-MM-DD"`, zero-padded.
    var dayKey: String = ""
    var reviewed: Bool = false
    var acceptedAt: Date = Date.distantPast
    /// JSON of `DayPlan`.
    var planData: Data = Data()

    init() {}
}
