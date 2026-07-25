import SwiftUI

struct MustDosView: View {
    @ObservedObject var model: TodayModel
    @State private var draft = ""
    @State private var editingLabel: String?
    @State private var editedLabel = ""

    @Environment(\.colorScheme) private var colorScheme
    private var palette: DayViewPalette { DayViewPalette.current(for: colorScheme) }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("MUST-DOS")
                    .font(.caption2).bold().kerning(1.2).foregroundStyle(palette.mint)
                Spacer()
                Text("\(model.snapshot.plannedObligations.count + model.snapshot.completedObligations.count)/3")
                    .font(.caption).foregroundStyle(palette.muted)
            }

            if model.snapshot.plannedObligations.isEmpty && model.snapshot.completedObligations.isEmpty {
                Text("Choose up to three things that make today count.")
                    .font(.caption).foregroundStyle(palette.muted)
            }

            ForEach(model.snapshot.plannedObligations, id: \.self) { label in
                obligationRow(label)
            }
            ForEach(model.snapshot.completedObligations, id: \.self) { label in
                HStack(spacing: 8) {
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(palette.mint)
                    Text(label).strikethrough().foregroundStyle(palette.muted)
                    Spacer()
                }
            }

            if model.snapshot.plannedObligationSlotsRemaining > 0 {
                HStack {
                    TextField("Add a must-do", text: $draft)
                        .textFieldStyle(.roundedBorder)
                        .onSubmit(addDraft)
                    Button(action: addDraft) {
                        Image(systemName: "plus")
                    }
                    .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .help("Add must-do")
                }
            }
        }
        .dayViewPanel(palette)
    }

    @ViewBuilder
    private func obligationRow(_ label: String) -> some View {
        if editingLabel == label {
            HStack {
                TextField("Must-do", text: $editedLabel)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit(commitEdit)
                Button("Save", action: commitEdit)
                    .keyboardShortcut(.defaultAction)
                Button("Cancel") { editingLabel = nil }
                    .keyboardShortcut(.cancelAction)
            }
        } else {
            HStack(spacing: 8) {
                Button {
                    model.completePlannedObligation(label)
                } label: {
                    Image(systemName: "circle")
                }
                .buttonStyle(.plain)
                .help("Mark as completed")
                Text(label).foregroundStyle(palette.cloud)
                Spacer()
                Button {
                    editedLabel = label
                    editingLabel = label
                } label: {
                    Image(systemName: "pencil")
                }
                .buttonStyle(.plain)
                .help("Rename")
                Button(role: .destructive) {
                    model.removePlannedObligation(label)
                } label: {
                    Image(systemName: "trash")
                }
                .buttonStyle(.plain)
                .help("Remove and free the slot")
            }
        }
    }

    private func addDraft() {
        let clean = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        model.addPlannedObligation(clean)
        draft = ""
    }

    private func commitEdit() {
        guard let old = editingLabel else { return }
        let clean = editedLabel.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        model.editPlannedObligation(old, newLabel: clean)
        editingLabel = nil
    }
}
