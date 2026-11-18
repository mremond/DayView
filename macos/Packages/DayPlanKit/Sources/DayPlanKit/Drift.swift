import Foundation

public enum AppClassification: Sendable, Hashable {
    case onGoal, offGoal, neutral
}

public enum AppClassifier {
    public static let dayViewBundleIDs: Set<String> = ["fr.dayview.app", "fr.dayview.app.debug"]

    /// DayView itself, an unknown app and any app in an area with no on-goal apps are neutral.
    public static func classify(bundleID: String?, area: Area) -> AppClassification {
        guard let bundleID, !dayViewBundleIDs.contains(bundleID), !area.onGoalApps.isEmpty else { return .neutral }
        return area.onGoalApps.contains { $0.bundleID == bundleID } ? .onGoal : .offGoal
    }
}

public enum DriftEvent: Sendable, Hashable {
    case driftReminder(areaID: AreaID)
    case cueIgnored(areaID: AreaID, cueAt: Date)
}

/// Turns once-a-second frontmost-app samples into drift reminders and ignored-cue events.
public struct DriftMonitor: Sendable {
    public static let driftThreshold: TimeInterval = 180
    public static let reminderSpacing: TimeInterval = 600
    public static let ignoredWindow: TimeInterval = 300
    public static let maxSampleGap: TimeInterval = 5

    private struct Watch: Sendable {
        let areaID: AreaID
        let cueAt: Date
        var lastSample: Date
    }

    private var currentArea: AreaID?
    private var streakStart: Date?
    private var lastSample: Date?
    private var lastReminder: Date?
    private var watch: Watch?

    public init() {}

    public mutating func sample(_ c: AppClassification, areaID: AreaID?, at now: Date) -> [DriftEvent] {
        guard let areaID else {
            resetAll()
            return []
        }
        if areaID != currentArea {
            // A watch opened for this area before its first sample survives; advanceWatch drops other areas'.
            let pending = watch
            resetAll()
            watch = pending
            currentArea = areaID
        }
        if let last = lastSample, now.timeIntervalSince(last) > Self.maxSampleGap { streakStart = nil }
        lastSample = now

        var events: [DriftEvent] = []
        if let event = advanceWatch(c, areaID: areaID, now: now) { events.append(event) }
        if let event = advanceStreak(c, areaID: areaID, now: now) { events.append(event) }
        return events
    }

    /// Starts watching whether the user ignores the cue; only possible when the area is measurable.
    public mutating func cueDelivered(areaID: AreaID, measurable: Bool, at: Date) {
        watch = measurable ? Watch(areaID: areaID, cueAt: at, lastSample: at) : nil
    }

    private mutating func resetAll() {
        currentArea = nil
        streakStart = nil
        lastSample = nil
        lastReminder = nil
        watch = nil
    }

    private mutating func advanceWatch(_ c: AppClassification, areaID: AreaID, now: Date) -> DriftEvent? {
        guard var open = watch else { return nil }
        guard open.areaID == areaID, c == .offGoal,
              now.timeIntervalSince(open.lastSample) <= Self.maxSampleGap else {
            watch = nil
            return nil
        }
        if now.timeIntervalSince(open.cueAt) >= Self.ignoredWindow {
            watch = nil
            return .cueIgnored(areaID: areaID, cueAt: open.cueAt)
        }
        open.lastSample = now
        watch = open
        return nil
    }

    private mutating func advanceStreak(_ c: AppClassification, areaID: AreaID, now: Date) -> DriftEvent? {
        guard c == .offGoal else {
            streakStart = nil
            return nil
        }
        let start = streakStart ?? now
        streakStart = start
        guard now.timeIntervalSince(start) >= Self.driftThreshold else { return nil }
        if let last = lastReminder, now.timeIntervalSince(last) < Self.reminderSpacing { return nil }
        lastReminder = now
        return .driftReminder(areaID: areaID)
    }
}
