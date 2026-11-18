import Testing
@testable import DayPlanKit

private let area = AreaID()

private func block(_ weekday: Int, _ start: Int, _ end: Int, intention: String = "") -> TemplateBlock {
    TemplateBlock(id: BlockID(), weekdayISO: weekday, startMinute: start, endMinute: end, areaID: area, intention: intention)
}

@Suite struct TemplateRulesTests {
    @Test func snapsToFiveMinutes() {
        #expect(TemplateRules.snapped(62) == 60)
        #expect(TemplateRules.snapped(63) == 65)
        #expect(TemplateRules.snapped(-4) == 0)
        #expect(TemplateRules.snapped(1443) == 1440)
    }

    @Test func wellFormed() {
        #expect(TemplateRules.isWellFormed(block(1, 540, 720)))
        #expect(TemplateRules.isWellFormed(block(1, 1380, 1440)))
        #expect(!TemplateRules.isWellFormed(block(1, 720, 720)))
        #expect(!TemplateRules.isWellFormed(block(1, 720, 540)))
        #expect(!TemplateRules.isWellFormed(block(1, 541, 600)))
        #expect(!TemplateRules.isWellFormed(block(1, 1380, 1445)))
        #expect(!TemplateRules.isWellFormed(block(0, 540, 720)))
        #expect(!TemplateRules.isWellFormed(block(8, 540, 720)))
    }

    @Test func overlapsSameWeekdayOnly() {
        let a = block(1, 540, 720)
        let b = block(1, 700, 800)
        let c = block(1, 800, 900)
        let d = block(2, 540, 720)
        #expect(TemplateRules.overlappingIDs([a, b, c, d]) == [a.id, b.id])
    }

    @Test func validDay() {
        let a = block(1, 540, 720)
        let b = block(1, 700, 800)
        let c = block(1, 800, 900)
        #expect(TemplateRules.isValidDay([a, c]))
        #expect(!TemplateRules.isValidDay([a, b]))
        #expect(!TemplateRules.isValidDay([block(1, 541, 600)]))
    }

    @Test func copyDayReplacesTargets() {
        let a = block(1, 540, 720, intention: "Deep")
        let c = block(1, 800, 900, intention: "Admin")
        let e = block(3, 600, 660)
        let f = block(5, 480, 540)
        let result = TemplateRules.copyingDay(1, to: [1, 3, 5], in: [a, c, e, f])
        let monday = result.filter { $0.weekdayISO == 1 }
        #expect(monday == [a, c])
        let oldIDs: Set<BlockID> = [a.id, c.id, e.id, f.id]
        var seen = Set<BlockID>()
        for day in [3, 5] {
            let blocks = result.filter { $0.weekdayISO == day }.sorted { $0.startMinute < $1.startMinute }
            #expect(blocks.map(\.startMinute) == [540, 800])
            #expect(blocks.map(\.endMinute) == [720, 900])
            #expect(blocks.map(\.areaID) == [a.areaID, c.areaID])
            #expect(blocks.map(\.intention) == ["Deep", "Admin"])
            for b in blocks {
                #expect(!oldIDs.contains(b.id))
                #expect(seen.insert(b.id).inserted)
            }
            #expect(TemplateRules.isValidDay(blocks))
        }
        #expect(result.count == 6)
    }
}
