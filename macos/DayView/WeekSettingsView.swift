import SwiftUI
import DayPlanKit

/// Week tab: edits one weekday of the weekly template as a local draft that is saved
/// only while it is valid.
struct WeekSettingsView: View {
    @ObservedObject var plan: PlanModel
    @Environment(\.colorScheme) private var scheme
    @State private var weekday = 1
    @State private var draft: [TemplateBlock] = []
    @State private var showCopy = false

    private var palette: DayViewPalette { DayViewPalette.current(for: scheme) }
    private var isValid: Bool { TemplateRules.isValidDay(draft) }
    private var storedDay: [TemplateBlock] { plan.template.filter { $0.weekdayISO == weekday } }
    private var illFormed: Set<BlockID> {
        Set(draft.filter { !TemplateRules.isWellFormed($0) }.map(\.id))
    }
    private var badRows: Set<BlockID> { illFormed.union(TemplateRules.overlappingIDs(draft)) }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if !plan.isPersistent { UnsavedBanner() }
            Picker("", selection: $weekday) {
                ForEach(1...7, id: \.self) { Text(WeekSettingsView.weekdayLabel($0)).tag($0) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()

            if draft.isEmpty {
                Text(L("No blocks on this day."))
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    VStack(spacing: 6) {
                        ForEach($draft, id: \.id) { $block in
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
                    .disabled(newBlockStart >= 1440 || plan.areas.isEmpty)
                Spacer()
                Button(L("Copy this day to…")) { showCopy = true }
                    .disabled(!isValid || draft.isEmpty)
                    .popover(isPresented: $showCopy) {
                        CopyDayPopover(source: weekday) { targets in
                            plan.copyDay(weekday, to: targets)
                            showCopy = false
                        }
                    }
            }
        }
        .padding()
        .onAppear(perform: reload)
        .onChange(of: weekday) { _, _ in reload() }
        .onChange(of: plan.areas) { _, areas in
            let known = Set(areas.map(\.id))
            draft.removeAll { !known.contains($0.areaID) }
        }
        .onChange(of: plan.template) { _, _ in
            if isValid && Set(draft) != Set(storedDay) { reload() }
        }
        .onChange(of: draft) { _, _ in
            if isValid && Set(draft) != Set(storedDay) { _ = plan.replaceDay(weekday, with: draft) }
        }
    }

    private func row(_ binding: Binding<TemplateBlock>) -> some View {
        let block = binding.wrappedValue
        return HStack(spacing: 6) {
            timePicker(L("Start"), minute: binding.startMinute, range: 0...1435)
            timePicker(L("End"), minute: binding.endMinute, range: 5...1440)
            Picker(L("Area"), selection: binding.areaID) {
                ForEach(plan.areas) { area in
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
                draft.removeAll { $0.id == block.id }
            } label: {
                Image(systemName: "trash")
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(L("Delete"))
        }
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

    private var newBlockStart: Int {
        draft.map(\.endMinute).max() ?? 540
    }

    private func addBlock() {
        guard let area = plan.areas.first else { return }
        let start = TemplateRules.snapped(newBlockStart)
        let end = min(start + 60, 1440)
        guard end > start else { return }
        draft.append(TemplateBlock(
            id: BlockID(), weekdayISO: weekday, startMinute: start, endMinute: end,
            areaID: area.id, intention: ""
        ))
    }

    private func reload() {
        draft = storedDay
    }

    // MARK: Labels

    /// ISO weekday (1 = Monday) to the Sunday-first symbol array.
    static func weekdayLabel(_ iso: Int) -> String {
        Calendar.current.shortStandaloneWeekdaySymbols[iso % 7]
    }

    fileprivate static var uses12Hour: Bool {
        (DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: .current) ?? "").contains("a")
    }

    fileprivate static func formatTime(_ minute: Int) -> String {
        if minute >= 1440 { return uses12Hour ? L("Midnight") : L("24:00") }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let base = DateComponents(calendar: calendar, timeZone: .current, year: 2001, month: 1, day: 1).date ?? Date()
        let date = base.addingTimeInterval(TimeInterval(minute * 60))
        return date.formatted(.dateTime.hour().minute().locale(.current))
    }
}

/// Time labels for minutes 0...1440 in 5-minute steps, formatted once per locale / 12-24 h setting.
private struct TimeLabels {
    private static var cached: (key: String, value: TimeLabels)?
    private let labels: [Int: String]

    static var current: TimeLabels {
        let key = "\(Locale.current.identifier)|\(WeekSettingsView.uses12Hour)"
        if let cached, cached.key == key { return cached.value }
        let table = Dictionary(uniqueKeysWithValues: stride(from: 0, through: 1440, by: TemplateRules.step).map {
            ($0, WeekSettingsView.formatTime($0))
        })
        let value = TimeLabels(labels: table)
        cached = (key, value)
        return value
    }

    func label(_ minute: Int) -> String { labels[minute] ?? WeekSettingsView.formatTime(minute) }
}

private struct CopyDayPopover: View {
    let source: Int
    let onCopy: (Set<Int>) -> Void
    @State private var chosen: Set<Int> = []

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach((1...7).filter { $0 != source }, id: \.self) { day in
                Toggle(WeekSettingsView.weekdayLabel(day), isOn: Binding(
                    get: { chosen.contains(day) },
                    set: { on in
                        if on { chosen.insert(day) } else { chosen.remove(day) }
                    }
                ))
            }
            Text(L("Blocks on the chosen days are replaced."))
                .font(.caption)
                .foregroundStyle(.secondary)
            HStack {
                Spacer()
                Button(L("Copy")) { onCopy(chosen) }
                    .disabled(chosen.isEmpty)
            }
        }
        .padding()
    }
}
