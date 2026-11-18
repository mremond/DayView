import SwiftUI
import DayPlanKit

/// Rows of template blocks for one day: time pickers, area, intention, delete, the
/// invalid-row message and "Add block". Shared by the Week tab and the morning check.
struct BlockListEditor<Trailing: View>: View {
    @Binding var blocks: [TemplateBlock]
    let areas: [Area]
    let weekdayISO: Int
    let isLocked: (BlockID) -> Bool
    let emptyText: String
    let trailing: Trailing
    @Environment(\.colorScheme) private var scheme

    private var palette: DayViewPalette { DayViewPalette.current(for: scheme) }
    private var isValid: Bool { TemplateRules.isValidDay(blocks) }
    private var illFormed: Set<BlockID> {
        Set(blocks.filter { !TemplateRules.isWellFormed($0) }.map(\.id))
    }
    private var badRows: Set<BlockID> { illFormed.union(TemplateRules.overlappingIDs(blocks)) }
    private var newBlockStart: Int { blocks.map(\.endMinute).max() ?? 540 }

    init(
        blocks: Binding<[TemplateBlock]>, areas: [Area], weekdayISO: Int,
        isLocked: @escaping (BlockID) -> Bool, emptyText: String,
        @ViewBuilder trailing: () -> Trailing
    ) {
        _blocks = blocks
        self.areas = areas
        self.weekdayISO = weekdayISO
        self.isLocked = isLocked
        self.emptyText = emptyText
        self.trailing = trailing()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            content
        }
    }

    @ViewBuilder private var content: some View {
        if blocks.isEmpty {
            Text(emptyText)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ScrollView {
                VStack(spacing: 6) {
                    ForEach($blocks, id: \.id) { $block in
                        row($block)
                    }
                }
            }
        }

        if !isValid {
            Text(illFormed.isEmpty ? L("Blocks overlap") : L("End must be after start"))
                .foregroundStyle(.red)
                .font(.callout)
        }

        HStack {
            Button(L("Add block"), action: addBlock)
                .disabled(newBlockStart >= 1440 || areas.isEmpty)
            Spacer()
            trailing
        }
    }

    private func row(_ binding: Binding<TemplateBlock>) -> some View {
        let block = binding.wrappedValue
        return HStack(spacing: 6) {
            timePicker(L("Start"), minute: binding.startMinute, range: 0...1435)
            timePicker(L("End"), minute: binding.endMinute, range: 5...1440)
            Picker(L("Area"), selection: binding.areaID) {
                ForEach(areas) { area in
                    HStack {
                        Circle().fill(palette.areaColor(area.colorIndex)).frame(width: 10, height: 10)
                        Text(area.name)
                    }
                    .tag(area.id)
                }
            }
            .labelsHidden()
            .frame(width: 130)
            TextField(L("Intention"), text: binding.intention)
            Button {
                blocks.removeAll { $0.id == block.id }
            } label: {
                Image(systemName: "trash")
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(L("Delete"))
        }
        .disabled(isLocked(block.id))
        .padding(4)
        .background(badRows.contains(block.id) ? Color.red.opacity(0.15) : Color.clear,
                    in: RoundedRectangle(cornerRadius: 6))
    }

    private func timePicker(_ title: String, minute: Binding<Int>, range: ClosedRange<Int>) -> some View {
        let labels = TimeLabels.current
        return Picker(title, selection: minute) {
            ForEach(Array(stride(from: range.lowerBound, through: range.upperBound, by: TemplateRules.step)), id: \.self) {
                Text(labels.label($0)).tag($0)
            }
        }
        .labelsHidden()
        .frame(width: 90)
    }

    private func addBlock() {
        guard let area = areas.first else { return }
        let start = TemplateRules.snapped(newBlockStart)
        let end = min(start + 60, 1440)
        guard end > start else { return }
        blocks.append(TemplateBlock(
            id: BlockID(), weekdayISO: weekdayISO, startMinute: start, endMinute: end,
            areaID: area.id, intention: ""
        ))
    }
}

/// Time labels for minutes 0...1440 in 5-minute steps, formatted once per locale / 12-24 h setting.
struct TimeLabels {
    private static var cached: (key: String, value: TimeLabels)?
    private let labels: [Int: String]

    static var current: TimeLabels {
        let key = "\(Locale.current.identifier)|\(uses12Hour)"
        if let cached, cached.key == key { return cached.value }
        let table = Dictionary(uniqueKeysWithValues: stride(from: 0, through: 1440, by: TemplateRules.step).map {
            ($0, formatTime($0))
        })
        let value = TimeLabels(labels: table)
        cached = (key, value)
        return value
    }

    func label(_ minute: Int) -> String { labels[minute] ?? Self.formatTime(minute) }

    private static var uses12Hour: Bool {
        (DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: .current) ?? "").contains("a")
    }

    private static func formatTime(_ minute: Int) -> String {
        if minute >= 1440 { return uses12Hour ? L("Midnight") : L("24:00") }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let base = DateComponents(calendar: calendar, timeZone: .current, year: 2001, month: 1, day: 1).date ?? Date()
        let date = base.addingTimeInterval(TimeInterval(minute * 60))
        return date.formatted(.dateTime.hour().minute().locale(.current))
    }
}

extension BlockListEditor where Trailing == EmptyView {
    init(
        blocks: Binding<[TemplateBlock]>, areas: [Area], weekdayISO: Int,
        isLocked: @escaping (BlockID) -> Bool, emptyText: String
    ) {
        self.init(blocks: blocks, areas: areas, weekdayISO: weekdayISO,
                  isLocked: isLocked, emptyText: emptyText) { EmptyView() }
    }
}
