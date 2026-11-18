import Foundation
import Testing
@testable import DayPlanKit

private let base = at("10:00")
private func t(_ seconds: TimeInterval) -> Date { base.addingTimeInterval(seconds) }

private func feed(_ acc: inout PresenceAccumulator, onGoal: Bool, from: TimeInterval, to: TimeInterval) {
    var s = from
    while s <= to {
        acc.sample(onGoal: onGoal, at: t(s))
        s += 1
    }
}

@Test func continuousStretch() {
    var acc = PresenceAccumulator()
    feed(&acc, onGoal: true, from: 0, to: 300)
    #expect(acc.finish(at: t(301)) == [DateInterval(start: t(0), end: t(300))])
}

@Test func blipBridged() {
    var acc = PresenceAccumulator()
    feed(&acc, onGoal: true, from: 0, to: 180)
    feed(&acc, onGoal: false, from: 181, to: 220)
    feed(&acc, onGoal: true, from: 221, to: 360)
    #expect(acc.finish(at: t(361)) == [DateInterval(start: t(0), end: t(360))])
}

@Test func longBreakSplits() {
    var acc = PresenceAccumulator()
    feed(&acc, onGoal: true, from: 0, to: 200)
    feed(&acc, onGoal: false, from: 201, to: 270)
    feed(&acc, onGoal: true, from: 271, to: 500)
    #expect(acc.finish(at: t(501)) == [
        DateInterval(start: t(0), end: t(200)), DateInterval(start: t(271), end: t(500))
    ])
}

@Test func shortStretchDropped() {
    var acc = PresenceAccumulator()
    feed(&acc, onGoal: true, from: 0, to: 90)
    #expect(acc.finish(at: t(91)).isEmpty)
}

@Test func sleepGapEnds() {
    var acc = PresenceAccumulator()
    feed(&acc, onGoal: true, from: 0, to: 240)
    acc.sample(onGoal: true, at: t(3600))
    #expect(acc.stretches == [DateInterval(start: t(0), end: t(240))])
    #expect(acc.finish(at: t(3601)) == [DateInterval(start: t(0), end: t(240))])
}

@Test func liveIncludesOpenStretchUnfiltered() {
    var acc = PresenceAccumulator()
    feed(&acc, onGoal: true, from: 0, to: 30)
    #expect(acc.stretches.isEmpty)
    #expect(acc.live(at: t(31)) == [DateInterval(start: t(0), end: t(30))])
}
