import Foundation
import Testing
@testable import DayPlanKit

private func interval(_ start: String, _ end: String) -> DateInterval {
    DateInterval(start: at(start), end: at(end))
}

private func block(_ start: String, _ end: String, area: AreaID = AreaID(), _ name: String = "Focus") -> PlannedBlock {
    PlannedBlock(id: BlockID(), start: at(start), end: at(end), areaID: area, intention: name)
}

private func cues(_ blocks: [PlannedBlock], meetings: [DateInterval] = []) -> [ScheduledCue] {
    let fitted = PlanFitter.fit(DayPlan(day: .nov18, blocks: blocks), meetings: meetings)
    return CueScheduler.schedule(fitted, meetings: meetings)
}

struct CueSchedulerTests {
    @Test func backToBackBlocks() {
        let health = AreaID()
        let a = block("09:00", "10:00", "Writing")
        let b = block("10:00", "11:00", area: health, "Health")
        let result = cues([a, b])
        #expect(result == [
            ScheduledCue(at: at("09:00"), kind: .blockStart, block: a),
            ScheduledCue(at: at("09:58"), kind: .blockEnding(next: .block(areaID: health, start: at("10:00"))), block: a),
            ScheduledCue(at: at("10:00"), kind: .blockStart, block: b),
            ScheduledCue(at: at("10:58"), kind: .blockEnding(next: .endOfPlan), block: b)
        ])
    }

    @Test func splitBlockHasResumeNotEarlyEnding() {
        let a = block("09:00", "12:00", "Writing")
        let result = cues([a], meetings: [interval("10:00", "10:30")])
        #expect(result == [
            ScheduledCue(at: at("09:00"), kind: .blockStart, block: a),
            ScheduledCue(at: at("10:30"), kind: .resume(until: at("12:00")), block: a),
            ScheduledCue(at: at("11:58"), kind: .blockEnding(next: .endOfPlan), block: a)
        ])
    }

    @Test func meetingBeforeNextBlock() {
        let result = cues([block("09:00", "10:00"), block("10:30", "11:00")], meetings: [interval("10:00", "10:30")])
        #expect(result.first { if case .blockEnding = $0.kind { true } else { false } }?.kind == .blockEnding(next: .meeting))
        #expect(!result.contains { if case .unplanned = $0.kind { true } else { false } })
    }

    @Test func gapCountsWithoutMeetingTime() {
        let result = cues([block("09:00", "10:00"), block("11:00", "12:00")], meetings: [interval("10:00", "10:50")])
        #expect(result[1].kind == .blockEnding(next: .meeting))
        #expect(!result.contains { if case .unplanned = $0.kind { true } else { false } })
    }

    @Test func longGapUnplanned() {
        let result = cues([block("09:00", "10:00"), block("13:00", "14:00")])
        #expect(result[1].kind == .blockEnding(next: .freeTime))
        let unplanned = result.filter { if case .unplanned = $0.kind { true } else { false } }
        #expect(unplanned == [ScheduledCue(at: at("10:00"), kind: .unplanned(until: at("13:00")), block: nil)])
    }

    @Test func unplannedWaitsForMeetingToEnd() {
        let result = cues([block("09:00", "10:00"), block("13:00", "14:00")], meetings: [interval("10:00", "10:30")])
        #expect(result.contains { $0.at == at("10:30") && $0.kind == .unplanned(until: at("13:00")) })
    }

    @Test func droppedBlockIgnored() {
        let area = AreaID()
        let c = block("10:05", "11:00", area: area)
        let result = cues([block("09:00", "10:00"), block("10:00", "10:30"), c], meetings: [interval("10:00", "10:30")])
        #expect(result[1].kind == .blockEnding(next: .block(areaID: area, start: at("10:05"))))
        #expect(result.count == 4)
    }

    @Test func noCueInsideMeetings() {
        let meetings = [interval("10:00", "10:30"), interval("10:20", "10:50")]
        let result = cues([block("09:00", "12:00"), block("13:00", "14:00")], meetings: meetings)
        let merged = [interval("10:00", "10:50")]
        #expect(!result.isEmpty)
        for cue in result {
            #expect(!merged.contains { $0.start < cue.at && cue.at < $0.end })
        }
    }
}
