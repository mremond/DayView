import SwiftUI
import DayPlanKit

/// Week tab: edits one weekday of the weekly template as a local draft that is saved
/// only while it is valid.
struct WeekSettingsView: View {
    @ObservedObject var plan: PlanModel
    @State private var weekday = 1
    @State private var draft: [TemplateBlock] = []
    @State private var showCopy = false

    private var isValid: Bool { TemplateRules.isValidDay(draft) }
    private var storedDay: [TemplateBlock] { plan.template.filter { $0.weekdayISO == weekday } }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if !plan.isPersistent { UnsavedBanner() }
            Picker("", selection: $weekday) {
                ForEach(1...7, id: \.self) { Text(WeekSettingsView.weekdayLabel($0)).tag($0) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()

            BlockListEditor(
                blocks: $draft, areas: plan.areas, weekdayISO: weekday,
                isLocked: { _ in false }, emptyText: L("No blocks on this day.")
            ) {
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

    private func reload() {
        draft = storedDay
    }

    // MARK: Labels

    /// ISO weekday (1 = Monday) to the Sunday-first symbol array.
    static func weekdayLabel(_ iso: Int) -> String {
        Calendar.current.shortStandaloneWeekdaySymbols[iso % 7]
    }
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
