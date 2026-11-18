import Foundation
import Testing
@testable import DayPlanKit

private func area(apps: [String]) -> Area {
    Area(id: AreaID(), name: "Writing", colorIndex: 0,
         onGoalApps: apps.map { OnGoalApp(bundleID: $0, displayName: $0) }, order: 0)
}

/// Feeds one sample per second from `from` through `through` (inclusive) and returns the events with their times.
private func feed(
    _ monitor: inout DriftMonitor, _ c: AppClassification, area: AreaID?, from: Date, through: Date
) -> [(Date, DriftEvent)] {
    var out: [(Date, DriftEvent)] = []
    var t = from
    while t <= through {
        for e in monitor.sample(c, areaID: area, at: t) { out.append((t, e)) }
        t = t.addingTimeInterval(1)
    }
    return out
}

@Test func classify() {
    let writing = area(apps: ["com.apple.Pages"])
    #expect(AppClassifier.classify(bundleID: "fr.dayview.app", area: writing) == .neutral)
    #expect(AppClassifier.classify(bundleID: "fr.dayview.app.debug", area: writing) == .neutral)
    #expect(AppClassifier.classify(bundleID: nil, area: writing) == .neutral)
    #expect(AppClassifier.classify(bundleID: "com.apple.Safari", area: area(apps: [])) == .neutral)
    #expect(AppClassifier.classify(bundleID: "com.apple.Pages", area: writing) == .onGoal)
    #expect(AppClassifier.classify(bundleID: "com.apple.Safari", area: writing) == .offGoal)
}

@Test func driftAfterThreeMinutes() {
    var m = DriftMonitor()
    let a = AreaID()
    let early = feed(&m, .offGoal, area: a, from: at("10:00"), through: at("10:02") + 59)
    #expect(early.isEmpty)
    #expect(m.sample(.offGoal, areaID: a, at: at("10:03")) == [.driftReminder(areaID: a)])
    #expect(m.sample(.offGoal, areaID: a, at: at("10:03") + 1).isEmpty)
}

@Test func reminderSpacing() {
    var m = DriftMonitor()
    let a = AreaID()
    let first = feed(&m, .offGoal, area: a, from: at("10:00"), through: at("10:03"))
    #expect(first.map(\.0) == [at("10:03")])
    _ = m.sample(.onGoal, areaID: a, at: at("10:03") + 10)
    let second = feed(&m, .offGoal, area: a, from: at("10:03") + 20, through: at("10:07"))
    #expect(second.isEmpty)
    // Continuous off-goal from 10:07:01 onwards fires again exactly at 10:13:00.
    let third = feed(&m, .offGoal, area: a, from: at("10:07") + 1, through: at("10:14"))
    #expect(third.map(\.0) == [at("10:13")])
}

@Test func continuousOffGoalFiresAgainAfterSpacing() {
    var m = DriftMonitor()
    let a = AreaID()
    let events = feed(&m, .offGoal, area: a, from: at("10:00"), through: at("10:14"))
    #expect(events.map(\.0) == [at("10:03"), at("10:13")])
}

@Test func neutralNeverDrifts() {
    var m = DriftMonitor()
    let events = feed(&m, .neutral, area: AreaID(), from: at("10:00"), through: at("10:20"))
    #expect(events.isEmpty)
}

@Test func sleepGapBreaksStreak() {
    var m = DriftMonitor()
    let a = AreaID()
    #expect(m.sample(.offGoal, areaID: a, at: at("10:00")).isEmpty)
    #expect(m.sample(.offGoal, areaID: a, at: at("10:00") + 1).isEmpty)
    let events = feed(&m, .offGoal, area: a, from: at("12:00"), through: at("12:03"))
    #expect(events.map(\.0) == [at("12:03")])
}

@Test func focusEndResets() {
    var m = DriftMonitor()
    let a = AreaID()
    #expect(feed(&m, .offGoal, area: a, from: at("10:00"), through: at("10:02") + 50).isEmpty)
    #expect(m.sample(.offGoal, areaID: nil, at: at("10:02") + 51).isEmpty)
    let events = feed(&m, .offGoal, area: a, from: at("10:02") + 52, through: at("10:06"))
    #expect(events.map(\.0) == [at("10:02") + 52 + 180])
}

@Test func cueIgnored() {
    var m = DriftMonitor()
    let a = AreaID()
    m.cueDelivered(areaID: a, measurable: true, at: at("14:00"))
    let events = feed(&m, .offGoal, area: a, from: at("14:00") + 1, through: at("14:06"))
    #expect(events.filter { $0.1 == .cueIgnored(areaID: a, cueAt: at("14:00")) }.map(\.0) == [at("14:05")])
    #expect(events.filter { if case .cueIgnored = $0.1 { true } else { false } }.count == 1)
}

@Test func cueNotIgnoredWhenOnGoal() {
    var m = DriftMonitor()
    let a = AreaID()
    m.cueDelivered(areaID: a, measurable: true, at: at("14:00"))
    var events = feed(&m, .offGoal, area: a, from: at("14:00") + 1, through: at("14:02") - 1)
    events += feed(&m, .onGoal, area: a, from: at("14:02"), through: at("14:02"))
    events += feed(&m, .offGoal, area: a, from: at("14:02") + 1, through: at("14:06"))
    #expect(events.allSatisfy { if case .cueIgnored = $0.1 { false } else { true } })
}

@Test func cueNotMeasurable() {
    var m = DriftMonitor()
    let a = AreaID()
    m.cueDelivered(areaID: a, measurable: false, at: at("14:00"))
    let events = feed(&m, .offGoal, area: a, from: at("14:00") + 1, through: at("14:06"))
    #expect(events.allSatisfy { if case .cueIgnored = $0.1 { false } else { true } })
}

@Test func cueWatchBrokenByGapAndByAreaChange() {
    let a = AreaID()
    var gap = DriftMonitor()
    gap.cueDelivered(areaID: a, measurable: true, at: at("14:00"))
    _ = gap.sample(.offGoal, areaID: a, at: at("14:00") + 1)
    #expect(gap.sample(.offGoal, areaID: a, at: at("14:05")).isEmpty)

    var other = DriftMonitor()
    other.cueDelivered(areaID: a, measurable: true, at: at("14:00"))
    _ = other.sample(.offGoal, areaID: a, at: at("14:00") + 1)
    _ = other.sample(.offGoal, areaID: AreaID(), at: at("14:00") + 2)
    #expect(other.sample(.offGoal, areaID: a, at: at("14:05")).isEmpty)
}

@Test func reminderSpacingSurvivesFocusRestart() {
    var m = DriftMonitor()
    let a = AreaID()
    let first = feed(&m, .offGoal, area: a, from: at("10:00"), through: at("10:03"))
    #expect(first.map(\.0) == [at("10:03")])
    _ = m.sample(.offGoal, areaID: nil, at: at("10:03") + 1)
    let second = feed(&m, .offGoal, area: a, from: at("10:03") + 2, through: at("10:13") + 5)
    #expect(second.map(\.0) == [at("10:13")])
}

/// A planned block paused for a meeting (or a locked screen) is sampled as neutral: off-goal time
/// before the pause does not carry over into a reminder, and the pause clears an ignored-cue watch.
@Test func pauseSampledAsNeutralNeverDriftsAndBreaksWatch() {
    var m = DriftMonitor()
    let a = AreaID()
    m.cueDelivered(areaID: a, measurable: true, at: at("10:00"))
    let before = feed(&m, .offGoal, area: a, from: at("10:00"), through: at("10:02"))
    #expect(before.isEmpty)
    let paused = feed(&m, .neutral, area: a, from: at("10:02") + 1, through: at("10:30"))
    #expect(paused.isEmpty)
    // Back from the meeting, off-goal again: a fresh 3-minute streak, and no ignored cue.
    let after = feed(&m, .offGoal, area: a, from: at("10:30") + 1, through: at("10:33") + 1)
    #expect(after.map(\.0) == [at("10:33") + 1])
    #expect(!after.contains { if case .cueIgnored = $0.1 { return true } else { return false } })
}
