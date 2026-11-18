import SwiftUI

/// The in-app drift reminder shown above the ring until dismissed or the Focus ends.
struct DriftBanner: View {
    let areaName: String
    let onDismiss: () -> Void

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let palette = DayViewPalette.current(for: colorScheme)
        HStack {
            Text(LF("You planned %@", areaName))
                .font(.body).foregroundStyle(palette.cloud)
            Spacer()
            Button(L("Dismiss"), action: onDismiss)
                .buttonStyle(.borderless)
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 10).fill(palette.amber.opacity(0.18)))
    }
}
