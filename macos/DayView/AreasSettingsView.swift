import SwiftUI
import DayPlanKit

/// Areas tab: ordered list on the left, editor for the selected area on the right.
struct AreasSettingsView: View {
    @ObservedObject var plan: PlanModel
    @ObservedObject var today: TodayModel
    @Environment(\.colorScheme) private var scheme
    @State private var selection: AreaID?
    @State private var installed: [OnGoalApp] = []
    @State private var capPrompt: CapPrompt?

    private var palette: DayViewPalette { DayViewPalette.current(for: scheme) }

    var body: some View {
        HStack(spacing: 0) {
            VStack(spacing: 0) {
                List(selection: $selection) {
                    ForEach(plan.areas) { area in
                        HStack {
                            Circle().fill(palette.areaColor(area.colorIndex)).frame(width: 10, height: 10)
                            Text(area.name)
                        }
                        .tag(area.id)
                    }
                    .onMove { plan.moveAreas(from: $0, to: $1) }
                }
                Button(L("Add area")) {
                    plan.addArea()
                    selection = plan.areas.last?.id
                }
                .padding(8)
            }
            .frame(width: 190)
            Divider()
            Group {
                // Hidden while the Cap question is open so the editor is created afterwards
                // from the migrated area (a stale draft would write the old values back).
                if capPrompt == nil, let area = plan.areas.first(where: { $0.id == selection }) {
                    AreaEditor(plan: plan, area: area, installed: installed, palette: palette)
                        .id(area.id)
                } else {
                    Spacer()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .onAppear {
            if selection == nil { selection = plan.areas.first?.id }
            askCapQuestionIfNeeded()
        }
        .sheet(item: $capPrompt) { prompt in
            CapMigrationSheet(
                prompt: prompt, areas: plan.areas, palette: palette,
                onChoose: { area in
                    plan.save(prompt.applying(to: area))
                    selection = area.id
                    finishCapQuestion()
                },
                onSkip: finishCapQuestion
            )
        }
        .onChange(of: plan.areas) { _, areas in
            if !areas.contains(where: { $0.id == selection }) { selection = areas.first?.id }
        }
        .task {
            installed = await Task.detached { InstalledApps.scan() }.value
        }
    }

    private func askCapQuestionIfNeeded() {
        let snapshot = today.snapshot
        let deadline = snapshot.goalHasDeadline
            ? Date(timeIntervalSince1970: Double(snapshot.goalDeadlineEpochMillis) / 1000)
            : nil
        let prompt = CapPrompt(
            goalTitle: snapshot.goalTitle,
            goalDeadline: deadline,
            onGoalApps: today.onGoalApps().map { OnGoalApp(bundleID: $0.bundleId, displayName: $0.displayName) }
        )
        if prompt.needsQuestion(alreadyAsked: plan.capQuestionAsked) { capPrompt = prompt }
    }

    private func finishCapQuestion() {
        plan.capQuestionAsked = true
        capPrompt = nil
    }
}

private struct AreaEditor: View {
    @ObservedObject var plan: PlanModel
    let installed: [OnGoalApp]
    let palette: DayViewPalette
    @State private var draft: Area
    @State private var confirmDelete = false
    @FocusState private var focused: Bool

    init(plan: PlanModel, area: Area, installed: [OnGoalApp], palette: DayViewPalette) {
        self.plan = plan
        self.installed = installed
        self.palette = palette
        _draft = State(initialValue: area)
    }

    private var blocks: Int { plan.blockCount(for: draft.id) }

    // Installed apps plus the stored ones, so a stored but uninstalled app stays visible.
    private var appChoices: [OnGoalApp] {
        var byID: [String: OnGoalApp] = [:]
        for app in installed + draft.onGoalApps { byID[app.bundleID] = byID[app.bundleID] ?? app }
        return byID.values.sorted {
            $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending
        }
    }

    var body: some View {
        Form {
            TextField(L("Name"), text: $draft.name)
                .focused($focused)
                .onSubmit(commit)
            LabeledContent(L("Colour")) {
                HStack(spacing: 6) {
                    ForEach(0..<AreaPalette.count, id: \.self) { index in
                        Circle()
                            .fill(palette.areaColor(index))
                            .frame(width: 18, height: 18)
                            .overlay(Circle().stroke(Color.primary, lineWidth: draft.colorIndex == index ? 2 : 0))
                            .onTapGesture {
                                draft.colorIndex = index
                                commit()
                            }
                    }
                }
            }
            Section(L("Goal")) {
                TextField(L("Goal"), text: $draft.goalTitle)
                    .focused($focused)
                    .onSubmit(commit)
                Toggle(L("Deadline"), isOn: Binding(
                    get: { draft.goalDeadline != nil },
                    set: { on in
                        draft.goalDeadline = on ? Date() : nil
                        commit()
                    }
                ))
                if let deadline = draft.goalDeadline {
                    DatePicker(L("Deadline"), selection: Binding(
                        get: { deadline },
                        set: { draft.goalDeadline = $0; commit() }
                    ), displayedComponents: .date)
                }
            }
            Section(L("On-goal apps")) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 2) {
                        ForEach(appChoices, id: \.bundleID) { app in
                            Toggle(app.displayName, isOn: Binding(
                                get: { draft.onGoalApps.contains { $0.bundleID == app.bundleID } },
                                set: { on in
                                    draft.onGoalApps.removeAll { $0.bundleID == app.bundleID }
                                    if on { draft.onGoalApps.append(app) }
                                    commit()
                                }
                            ))
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(height: 120)
            }
            Button(L("Delete area"), role: .destructive) {
                if blocks > 0 { confirmDelete = true } else { plan.deleteArea(draft.id) }
            }
            .disabled(plan.areas.count <= 1)
        }
        .formStyle(.grouped)
        .onChange(of: focused) { _, isFocused in
            if !isFocused { commit() }
        }
        .onDisappear(perform: commit)
        .confirmationDialog(
            LF("Delete %@ and its %lld blocks?", draft.name, blocks),
            isPresented: $confirmDelete
        ) {
            Button(L("Delete"), role: .destructive) { plan.deleteArea(draft.id) }
            Button(L("Cancel"), role: .cancel) {}
        }
    }

    /// Editable fields only: `order` is owned by the store (drag-reorder), so the draft's
    /// stale copy must never be written back.
    private func commit() {
        guard var stored = plan.areas.first(where: { $0.id == draft.id }) else { return }
        let before = stored
        stored.name = draft.name
        stored.colorIndex = draft.colorIndex
        stored.goalTitle = draft.goalTitle
        stored.goalDeadline = draft.goalDeadline
        stored.onGoalApps = draft.onGoalApps
        if stored != before { plan.save(stored) }
    }
}
