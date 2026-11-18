import SwiftUI
import DayPlanKit

/// Time formatting shared by the plan views. Respects the system 12/24 h setting.
enum PlanTimeFormat {
    static func time(_ date: Date) -> String {
        date.formatted(.dateTime.hour().minute())
    }

    /// "10:00–10:30" with an en dash.
    static func range(_ start: Date, _ end: Date) -> String {
        "\(time(start))–\(time(end))"
    }
}

/// The morning check: today's plan fitted around meetings, with Adjust, Start my day / Done
/// and Not now.
struct MorningCheckSheet: View {
    @ObservedObject var todayPlan: TodayPlanModel
    let areas: [Area]
    @Environment(\.colorScheme) private var scheme
    @State private var draft: [TemplateBlock] = []
    @State private var seed: [TemplateBlock] = []
    @State private var isAdjusting = false
    /// The day this sheet was built for; saves are refused if today has moved on.
    private let day: LocalDay

    private let calendar = Calendar.autoupdatingCurrent

    init(todayPlan: TodayPlanModel, areas: [Area]) {
        self.todayPlan = todayPlan
        self.areas = areas
        day = todayPlan.state.day
    }

    private var state: TodayPlanState { todayPlan.state }
    private var palette: DayViewPalette { DayViewPalette.current(for: scheme) }
    private var weekdayISO: Int { state.day.weekdayISO(in: calendar) }
    private var hasEdits: Bool { isAdjusting && draft != seed }
    /// What the primary button saves: the draft while adjusting, otherwise what is shown.
    private var blocksToSave: [TemplateBlock] {
        isAdjusting ? draft : state.plan.minuteBlocks(calendar: calendar)
    }
    private var isValid: Bool { TemplateRules.isValidDay(blocksToSave) }

    /// Opens the editor on the current plan; collapsing is not possible while it holds unsaved edits.
    private func toggleAdjust() {
        if isAdjusting {
            guard !hasEdits else { return }
            isAdjusting = false
        } else {
            seed = state.plan.minuteBlocks(calendar: calendar)
            draft = seed
            isAdjusting = true
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(state.day.start(in: calendar).formatted(.dateTime.weekday(.wide).day().month(.wide).year()))
                .font(.title2.bold())
            if let summary = todayPlan.reviewSummary {
                Text(LF("Last 7 days: plan reviewed %lld/%lld days", summary.reviewed, summary.days))
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            if isAdjusting {
                BlockListEditor(
                    blocks: $draft, areas: areas, weekdayISO: weekdayISO,
                    isLocked: isLocked, emptyText: L("No plan for today")
                )
                .frame(minHeight: 160)
            } else {
                blockList
            }
            Divider()
            buttons
        }
        .padding(20)
        .frame(width: isAdjusting ? 640 : 480)
        .frame(minHeight: 200, maxHeight: 640)
    }

    // MARK: Fitted list

    @ViewBuilder private var blockList: some View {
        if state.fitted.isEmpty {
            Text(L("No plan for today"))
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, minHeight: 80)
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(state.fitted.sorted { $0.block.start < $1.block.start }, id: \.block.id) { row($0) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private func row(_ fitted: FittedBlock) -> some View {
        let block = fitted.block
        let area = areas.first { $0.id == block.areaID }
        let range: String = {
            if fitted.isDropped { return PlanTimeFormat.range(block.start, block.end) }
            let parts = fitted.parts
            return parts.map { PlanTimeFormat.range($0.start, $0.end) }.joined(separator: ", ")
        }()
        return VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 8) {
                Circle().fill(palette.areaColor(area?.colorIndex ?? 0)).frame(width: 10, height: 10)
                Text(area?.name ?? "").fontWeight(.semibold)
                Text(range)
                    .foregroundStyle(.secondary)
                    .strikethrough(fitted.isDropped)
            }
            if !block.intention.isEmpty {
                Text(block.intention).padding(.leading, 18)
            }
            ForEach(Array(fitted.notes.enumerated()), id: \.offset) { _, note in
                Text(noteText(note))
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .padding(.leading, 18)
            }
        }
    }

    private func noteText(_ note: FitNote) -> String {
        switch note {
        case .splitAround(let meeting):
            return LF("Split around the %@ meeting", PlanTimeFormat.range(meeting.start, meeting.end))
        case .trimmed: return L("Shortened by a meeting")
        case .dropped: return L("Covered by meetings")
        }
    }

    // MARK: Actions

    /// Once accepted, blocks that have already ended are read-only.
    private func isLocked(_ id: BlockID) -> Bool {
        guard state.isAccepted else { return false }
        let parts = calendar.dateComponents([.hour, .minute], from: Date())
        let nowMinute = (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
        let original = state.plan.minuteBlocks(calendar: calendar).first { $0.id == id }
        return original.map { $0.endMinute <= nowMinute } ?? false
    }

    private var buttons: some View {
        HStack {
            Button(L("Adjust")) { toggleAdjust() }
                .disabled(hasEdits)
            Spacer()
            Button(L("Not now")) { todayPlan.isMorningCheckPresented = false }
                .keyboardShortcut(.cancelAction)
            if state.isAccepted {
                Button(L("Finished plan")) { todayPlan.saveEdits(blocksToSave, for: day) }
                    .keyboardShortcut(.defaultAction)
                    .disabled(!isValid)
            } else {
                Button(L("Start my day")) { todayPlan.accept(blocksToSave, for: day) }
                    .keyboardShortcut(.defaultAction)
                    .disabled(!isValid)
            }
        }
    }
}
