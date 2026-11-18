import Foundation
import Testing
@testable import DayPlanKit

struct DayMinutesTests {
    @Test func roundTrip() {
        let area = AreaID()
        let blocks = [
            TemplateBlock(id: BlockID(), weekdayISO: 3, startMinute: 540, endMinute: 720, areaID: area, intention: "Writing"),
            TemplateBlock(id: BlockID(), weekdayISO: 3, startMinute: 1380, endMinute: 1440, areaID: AreaID(), intention: "")
        ]
        let plan = DayPlan.fromMinuteBlocks(blocks, day: .nov18, calendar: paris)
        #expect(plan.blocks[1].end == at("00:00", on: LocalDay(year: 2026, month: 11, day: 19)))
        #expect(plan.minuteBlocks(calendar: paris) == blocks)
    }

    @Test func dstGap() {
        let sunday = LocalDay(year: 2026, month: 3, day: 29)
        let block = TemplateBlock(id: BlockID(), weekdayISO: 1, startMinute: 150, endMinute: 240, areaID: AreaID(), intention: "")
        let plan = DayPlan.fromMinuteBlocks([block], day: sunday, calendar: paris)
        let start = paris.dateComponents([.hour, .minute], from: plan.blocks[0].start)
        #expect(start.hour == 3 && start.minute == 0)
        let back = plan.minuteBlocks(calendar: paris)
        #expect(back[0].startMinute == 180 && back[0].endMinute == 240 && back[0].weekdayISO == 7)
    }

    @Test func instantiateKeepsTemplateIDs() {
        let t = TemplateBlock(id: BlockID(), weekdayISO: 3, startMinute: 540, endMinute: 600, areaID: AreaID(), intention: "")
        let plan = DayPlan.instantiate(template: [t], day: .nov18, calendar: paris)
        #expect(plan.blocks[0].id == t.id)
    }
}
