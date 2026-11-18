import Foundation

/// Rules for carrying a goal configured in the legacy Cap feature into an area.
public enum CapMigration {
    public static func needsQuestion(
        goalTitle: String,
        goalDeadline: Date?,
        onGoalApps: [OnGoalApp],
        alreadyAsked: Bool
    ) -> Bool {
        guard !alreadyAsked else { return false }
        let hasTitle = !goalTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        return hasTitle || goalDeadline != nil || !onGoalApps.isEmpty
    }

    public static func applying(
        goalTitle: String,
        goalDeadline: Date?,
        onGoalApps: [OnGoalApp],
        to area: Area
    ) -> Area {
        var result = area
        result.goalTitle = goalTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        result.goalDeadline = goalDeadline
        let existing = Set(area.onGoalApps.map(\.bundleID))
        result.onGoalApps = area.onGoalApps + onGoalApps.filter { !existing.contains($0.bundleID) }
        return result
    }
}
