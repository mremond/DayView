import SwiftUI
import DayViewKit

/// Quick capture: required motif, recent-motif chips, an approximate duration, an optional
/// note. Local @State draft — a modal form is not persisted state; Add commits through the
/// bridge (addDetour spans `durationMinutes` back from now) and dismisses.
struct DetourCaptureSheet: View {
    @ObservedObject var model: TodayModel
    @Binding var isPresented: Bool

    @Environment(\.colorScheme) private var colorScheme
    @State private var motif = ""
    @State private var note = ""
    @State private var durationMinutes = 15

    private static let durationChoices = [5, 15, 30, 45, 60]

    var body: some View {
        let palette = DayViewPalette.current(for: colorScheme)
        VStack(alignment: .leading, spacing: 12) {
            Text("What pulled you off the path?").font(.headline)
            TextField("E.g. unexpected call", text: $motif)
                .textFieldStyle(.roundedBorder)
            if !model.snapshot.recentDetourCategories.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(model.snapshot.recentDetourCategories, id: \.self) { cat in
                            Button(cat) { motif = cat }
                                .buttonStyle(.bordered)
                                .tint(palette.muted)
                                .lineLimit(1)
                                .contextMenu {
                                    Button("Forget \u{201C}\(cat)\u{201D}", role: .destructive) {
                                        model.forgetRecentDetourCategory(cat)
                                    }
                                }
                        }
                    }
                }
            }
            Text("APPROXIMATE DURATION").font(.caption2).bold().kerning(1).foregroundStyle(palette.muted)
            Picker("Duration", selection: $durationMinutes) {
                ForEach(Self.durationChoices, id: \.self) { Text("\($0) min").tag($0) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            TextField("Optional detail", text: $note)
                .textFieldStyle(.roundedBorder)
            HStack {
                Spacer()
                Button("Cancel") { isPresented = false }
                Button("Add") {
                    model.addDetour(category: motif, durationMinutes: Int32(durationMinutes), description: note)
                    isPresented = false
                }
                .keyboardShortcut(.defaultAction)
                .tint(palette.amber)
                .disabled(motif.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(20)
        .frame(width: 360)
    }
}
