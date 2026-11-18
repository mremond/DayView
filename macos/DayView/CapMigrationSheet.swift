import SwiftUI
import DayPlanKit

/// The legacy Cap (goal title, deadline, on-goal apps) awaiting a one-time choice of area.
struct CapPrompt: Identifiable {
    let id = UUID()
    let goalTitle: String
    let goalDeadline: Date?
    let onGoalApps: [OnGoalApp]

    var hasGoal: Bool {
        !goalTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || goalDeadline != nil
    }

    func needsQuestion(alreadyAsked: Bool) -> Bool {
        CapMigration.needsQuestion(
            goalTitle: goalTitle, goalDeadline: goalDeadline,
            onGoalApps: onGoalApps, alreadyAsked: alreadyAsked
        )
    }

    func applying(to area: Area) -> Area {
        CapMigration.applying(
            goalTitle: goalTitle, goalDeadline: goalDeadline,
            onGoalApps: onGoalApps, to: area
        )
    }
}

/// Asks once which area the current Cap belongs to. Not dismissible except through Skip.
struct CapMigrationSheet: View {
    let prompt: CapPrompt
    let areas: [Area]
    let palette: DayViewPalette
    let onChoose: (Area) -> Void
    let onSkip: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(prompt.hasGoal
                 ? L("Which area does your current goal belong to?")
                 : L("Which area do your on-goal apps belong to?"))
                .font(.headline)
            if !prompt.goalTitle.isEmpty {
                Text(prompt.goalTitle)
            }
            if let deadline = prompt.goalDeadline {
                Text(deadline.formatted(date: .abbreviated, time: .omitted))
                    .foregroundStyle(.secondary)
            }
            if !prompt.onGoalApps.isEmpty {
                Text(L("Apps:") + " " + prompt.onGoalApps.map(\.displayName).joined(separator: ", "))
                    .foregroundStyle(.secondary)
            }
            Divider()
            ForEach(areas) { area in
                Button {
                    onChoose(area)
                } label: {
                    HStack {
                        Circle().fill(palette.areaColor(area.colorIndex)).frame(width: 10, height: 10)
                        Text(area.name)
                        Spacer()
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.bordered)
            }
            HStack {
                Spacer()
                Button(L("Skip"), action: onSkip)
            }
        }
        .padding(20)
        .frame(width: 360)
        .interactiveDismissDisabled()
    }
}
