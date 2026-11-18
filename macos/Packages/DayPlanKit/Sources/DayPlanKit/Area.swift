import Foundation

/// An app that counts as "on goal" while working in an area.
public struct OnGoalApp: Sendable, Hashable, Codable {
    public var bundleID: String
    public var displayName: String

    public init(bundleID: String, displayName: String) {
        self.bundleID = bundleID
        self.displayName = displayName
    }
}

/// A life area (e.g. Writing, Work) that plan blocks belong to, with an optional goal.
public struct Area: Sendable, Hashable, Codable, Identifiable {
    public var id: AreaID
    public var name: String
    public var colorIndex: Int
    public var goalTitle: String
    public var goalDeadline: Date?
    public var onGoalApps: [OnGoalApp]
    public var order: Int

    public init(
        id: AreaID,
        name: String,
        colorIndex: Int,
        goalTitle: String = "",
        goalDeadline: Date? = nil,
        onGoalApps: [OnGoalApp] = [],
        order: Int
    ) {
        self.id = id
        self.name = name
        self.colorIndex = colorIndex
        self.goalTitle = goalTitle
        self.goalDeadline = goalDeadline
        self.onGoalApps = onGoalApps
        self.order = order
    }
}

public enum AreaPalette {
    public static let count = 8

    /// Maps any integer onto a valid palette index (positive modulo).
    public static func normalized(_ index: Int) -> Int {
        ((index % count) + count) % count
    }
}

public enum AreaSeed {
    /// The four starter areas, in the given order.
    public static func defaults(names: [String]) -> [Area] {
        precondition(names.count == 4, "AreaSeed expects exactly 4 area names")
        return names.enumerated().map { index, name in
            Area(id: AreaID(), name: name, colorIndex: index, order: index)
        }
    }
}
