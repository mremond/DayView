import SwiftUI
import DayViewKit

/// The closure ritual: name the work at the close rather than as a toll on starting.
/// Leaving before the term with anything but "Completed" costs a named detour — tapping
/// such an outcome unfolds the capture instead of closing, and only Confirm leaves.
struct FocusClosureSheet: View {
    @ObservedObject var model: TodayModel
    @Binding var isPresented: Bool

    @Environment(\.colorScheme) private var colorScheme
    @State private var intention = ""
    @State private var pendingOutcome: String?
    @State private var motif = ""
    @State private var detail = ""
    @State private var seeded = false

    private static let outcomes = ["COMPLETED", "PROGRESSED", "TO_RESUME"]

    private func label(_ outcome: String) -> String {
        switch outcome {
        case "COMPLETED": return "Completed"
        case "PROGRESSED": return "Progressed"
        default: return "Resume later"
        }
    }

    private func tint(_ outcome: String, _ palette: DayViewPalette) -> Color {
        switch outcome {
        case "COMPLETED": return palette.mint
        case "PROGRESSED": return palette.amber
        default: return palette.muted
        }
    }

    /// "Completed" is always free; the other two are tolled exactly when the snapshot says so.
    private func costsName(_ outcome: String) -> Bool {
        outcome != "COMPLETED" && model.snapshot.earlyExitCostsName
    }

    /// Must agree with the controller's own gate: closePomodoro treats a motif as unnamed
    /// only after sanitizeDetourCategory (which also strips commas, not just whitespace).
    /// A comma-only motif would otherwise read as non-blank here and silently fail there.
    private var motifIsBlank: Bool {
        DetoursKt.sanitizeDetourCategory(raw: motif).isEmpty
    }

    var body: some View {
        let palette = DayViewPalette.current(for: colorScheme)
        VStack(alignment: .leading, spacing: 12) {
            Text("Close this focus").font(.headline)
            Text("WHAT WAS IT?")
                .font(.caption2).bold().kerning(1).foregroundStyle(palette.muted)
            TextField("What are you focusing on?", text: $intention)
                .textFieldStyle(.roundedBorder)
            HStack(spacing: 8) {
                ForEach(Self.outcomes, id: \.self) { outcome in
                    Button(label(outcome)) {
                        if costsName(outcome) {
                            pendingOutcome = outcome
                        } else {
                            model.closeFocus(outcome, intention: intention, detourCategory: "", detourDescription: "")
                            isPresented = false
                        }
                    }
                    .buttonStyle(.bordered)
                    .tint(tint(outcome, palette))
                }
            }
            if let outcome = pendingOutcome {
                exitCapture(outcome: outcome, palette: palette)
            }
            HStack {
                Spacer()
                Button("Cancel") { isPresented = false }
            }
        }
        .padding(20)
        .frame(width: 380)
        .onAppear {
            // Seeded once, not per redraw: the field is a draft the user may edit while
            // ticks keep arriving.
            if !seeded {
                intention = model.snapshot.focusIntention
                seeded = true
            }
        }
        .onChange(of: model.snapshot.earlyExitCostsName) { _, costs in
            // The toll can lift under the user's feet: reaching the term mid-capture would
            // leave them naming a pull they no longer owe. The capture follows the toll,
            // not the tap that opened it.
            if !costs { pendingOutcome = nil }
        }
    }

    /// The exit toll: name the pull that takes you out, optionally describe it, then leave.
    private func exitCapture(outcome: String, palette: DayViewPalette) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("WHAT PULLS YOU AWAY?")
                .font(.caption2).bold().kerning(1).foregroundStyle(palette.amber)
            TextField("E.g. unexpected call", text: $motif)
                .textFieldStyle(.roundedBorder)
            if !model.snapshot.recentDetourCategories.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(model.snapshot.recentDetourCategories, id: \.self) { category in
                            Button(category) { motif = category }
                                .buttonStyle(.bordered)
                                .tint(palette.muted)
                                .lineLimit(1)
                        }
                    }
                }
            }
            TextField("Optional detail", text: $detail)
                .textFieldStyle(.roundedBorder)
            HStack {
                Spacer()
                Button("Confirm") {
                    model.closeFocus(outcome, intention: intention, detourCategory: motif, detourDescription: detail)
                    isPresented = false
                }
                .keyboardShortcut(.defaultAction)
                .tint(palette.amber)
                // The controller refuses a blank motif silently; never offer that path.
                .disabled(motifIsBlank)
            }
        }
    }
}
