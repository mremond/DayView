import SwiftUI
import DayViewKit

/// Today's detours: rename/adjust each, delete (with undo), add after the fact. Rows are
/// re-read from the snapshot every render — the controller re-sorts by start on commit, so
/// indices must not be cached across a mutation.
struct DetourListSheet: View {
    @ObservedObject var model: TodayModel
    @Binding var isPresented: Bool

    @Environment(\.colorScheme) private var colorScheme
    @State private var editing: IdentifiableIndex?
    @State private var showAdd = false
    @State private var canUndo = false

    var body: some View {
        let palette = DayViewPalette.current(for: colorScheme)
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("TODAY'S DETOURS").font(.caption).bold().kerning(1).foregroundStyle(palette.muted)
                Spacer()
                if canUndo {
                    Button("Undo") { model.restoreLastRemovedDetour(); canUndo = false }
                        .buttonStyle(.borderless)
                }
            }
            if model.snapshot.detours.isEmpty {
                Text("No detours declared today.").foregroundStyle(palette.muted)
            } else {
                ForEach(Array(model.snapshot.detours.enumerated()), id: \.offset) { index, entry in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(entry.category).foregroundStyle(palette.cloud)
                            Text("\(entry.timeRangeLabel) · \(entry.durationLabel)")
                                .font(.caption).foregroundStyle(palette.muted)
                        }
                        Spacer()
                        Button { editing = IdentifiableIndex(id: index) } label: { Image(systemName: "pencil") }
                            .buttonStyle(.borderless)
                        Button { model.removeDetour(index: Int32(index)); canUndo = true } label: { Image(systemName: "trash") }
                            .buttonStyle(.borderless).tint(palette.red)
                    }
                    .padding(.vertical, 4)
                }
            }
            HStack {
                Button("Add a detour") { showAdd = true }.tint(palette.amber)
                Spacer()
                Button("Close") { isPresented = false }
            }
        }
        .padding(20)
        .frame(width: 380)
        .sheet(isPresented: $showAdd) { DetourCaptureSheet(model: model, isPresented: $showAdd) }
        .sheet(item: $editing) { item in
            DetourEditSheet(model: model, index: item.id, entry: model.snapshot.detours[safe: item.id], isPresented: Binding(get: { editing != nil }, set: { if !$0 { editing = nil } }))
        }
    }
}

// Optional index into the snapshot list — nil if it changed out from under the sheet.
extension Array {
    subscript(safe index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}

// Wraps a plain Int index so it can be used with `.sheet(item:)` without a
// retroactive `Int: Identifiable` conformance on a stdlib type.
private struct IdentifiableIndex: Identifiable { let id: Int }

/// Edit one detour: rename, adjust the time span (via a duration re-pick anchored to the
/// existing end), change the note. Saves through updateDetour(index:).
struct DetourEditSheet: View {
    @ObservedObject var model: TodayModel
    let index: Int
    let entry: DetourEntry?
    @Binding var isPresented: Bool

    @Environment(\.colorScheme) private var colorScheme
    @State private var motif = ""
    @State private var note = ""
    @State private var start = Date()
    @State private var end = Date()
    @State private var seeded = false

    var body: some View {
        let palette = DayViewPalette.current(for: colorScheme)
        VStack(alignment: .leading, spacing: 12) {
            Text("EDIT DETOUR").font(.caption).bold().kerning(1).foregroundStyle(palette.muted)
            TextField("Motif", text: $motif).textFieldStyle(.roundedBorder)
            DatePicker("Start", selection: $start, displayedComponents: [.hourAndMinute])
            DatePicker("End", selection: $end, displayedComponents: [.hourAndMinute])
            TextField("Optional detail", text: $note).textFieldStyle(.roundedBorder)
            HStack {
                Spacer()
                Button("Cancel") { isPresented = false }
                Button("Save") {
                    model.updateDetour(
                        index: Int32(index),
                        startEpochMillis: Int64(start.timeIntervalSince1970 * 1000),
                        endEpochMillis: Int64(end.timeIntervalSince1970 * 1000),
                        category: motif,
                        description: note
                    )
                    isPresented = false
                }
                .keyboardShortcut(.defaultAction)
                .tint(palette.amber)
                .disabled(motif.trimmingCharacters(in: .whitespaces).isEmpty || end <= start)
            }
        }
        .padding(20)
        .frame(width: 360)
        .onAppear {
            guard !seeded, let entry else { return }
            motif = entry.category
            note = entry.description
            start = Date(timeIntervalSince1970: Double(entry.startEpochMillis) / 1000)
            end = Date(timeIntervalSince1970: Double(entry.endEpochMillis) / 1000)
            seeded = true
        }
    }
}
