import SwiftUI
import DayViewKit

struct FocusSessionDetailSheet: View {
    let session: FocusSessionSnapshot
    @Binding var isPresented: Bool

    @Environment(\.colorScheme) private var colorScheme
    private var palette: DayViewPalette { DayViewPalette.current(for: colorScheme) }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text("FOCUS SESSION")
                        .font(.caption2).bold().kerning(1.2).foregroundStyle(palette.mint)
                    Text(session.intention.isEmpty ? L("Untitled focus") : session.intention)
                        .font(.title3.weight(.semibold))
                }
                Spacer()
                Text(session.timeRangeLabel)
                    .font(.callout).monospacedDigit().foregroundStyle(palette.muted)
            }
            Divider()
            Grid(alignment: .leading, horizontalSpacing: 28, verticalSpacing: 10) {
                metric("Duration", session.durationLabel)
                metric("Engaged", session.engagedLabel)
                metric("Deep focus", session.deepFocusLabel)
                if !session.outcome.isEmpty {
                    metric("Outcome", outcomeLabel)
                }
            }
            HStack {
                Spacer()
                Button("Done") { isPresented = false }
                    .keyboardShortcut(.defaultAction)
                    .keyboardShortcut(.cancelAction)
            }
        }
        .padding(22)
        .frame(width: 390)
    }

    private func metric(_ label: LocalizedStringKey, _ value: String) -> some View {
        GridRow {
            Text(label).foregroundStyle(palette.muted)
            Text(value).monospacedDigit().foregroundStyle(palette.cloud)
        }
    }

    private var outcomeLabel: String {
        switch session.outcome {
        case "COMPLETED": return L("Completed")
        case "PROGRESSED": return L("Progressed")
        case "TO_RESUME": return L("Resume later")
        default: return session.outcome
        }
    }
}
