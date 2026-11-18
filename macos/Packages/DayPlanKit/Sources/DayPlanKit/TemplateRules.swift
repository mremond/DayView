import Foundation

/// Pure validation and editing rules for the weekly template.
public enum TemplateRules {
    public static let step = 5
    private static let minutesPerDay = 1440

    /// Nearest multiple of `step` (ties round up), clamped to 0...1440.
    public static func snapped(_ minute: Int) -> Int {
        let clamped = min(max(minute, 0), minutesPerDay)
        return ((clamped + step / 2) / step) * step
    }

    public static func isWellFormed(_ block: TemplateBlock) -> Bool {
        (1...7).contains(block.weekdayISO)
            && block.startMinute >= 0
            && block.startMinute < block.endMinute
            && block.endMinute <= minutesPerDay
            && block.startMinute % step == 0
            && block.endMinute % step == 0
    }

    /// IDs of every block overlapping another block on the same weekday. Touching edges do not overlap.
    public static func overlappingIDs(_ blocks: [TemplateBlock]) -> Set<BlockID> {
        var result = Set<BlockID>()
        for (index, a) in blocks.enumerated() {
            for b in blocks[(index + 1)...] where a.weekdayISO == b.weekdayISO {
                if a.startMinute < b.endMinute && b.startMinute < a.endMinute {
                    result.insert(a.id)
                    result.insert(b.id)
                }
            }
        }
        return result
    }

    public static func isValidDay(_ blocks: [TemplateBlock]) -> Bool {
        blocks.allSatisfy(isWellFormed) && overlappingIDs(blocks).isEmpty
    }

    /// Replaces each target weekday's blocks with fresh copies of the source day's blocks.
    public static func copyingDay(_ source: Int, to targets: Set<Int>, in template: [TemplateBlock]) -> [TemplateBlock] {
        let targets = targets.subtracting([source])
        let sourceBlocks = template.filter { $0.weekdayISO == source }
        var result = template.filter { !targets.contains($0.weekdayISO) }
        for weekday in targets.sorted() {
            for block in sourceBlocks {
                var copy = block
                copy.id = BlockID()
                copy.weekdayISO = weekday
                result.append(copy)
            }
        }
        return result
    }
}
