import SwiftUI

/// The closure ritual's outcome row — Completed / Progressed / Resume later — shared by
/// the main window's focus section and the mini window's focus card.
struct FocusClosureButtons: View {
    @ObservedObject var model: TodayModel

    var body: some View {
        HStack(spacing: 8) {
            Button("Completed") { model.closeFocus("COMPLETED") }
                .buttonStyle(.bordered)
                .tint(.green)
            Button("Progressed") { model.closeFocus("PROGRESSED") }
                .buttonStyle(.bordered)
                .tint(.orange)
            Button("Resume later") { model.closeFocus("TO_RESUME") }
                .buttonStyle(.bordered)
        }
    }
}
