import SwiftUI
import DayViewKit

/// A detour running on the stopwatch — opened by the exit toll when a focus is left early.
/// Mirrors the Compose OpenDetourPanel: the motif, an optional detail, the elapsed clock,
/// and the stop that commits the episode.
struct OpenDetourBanner: View {
    @ObservedObject var model: TodayModel

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let palette = DayViewPalette.current(for: colorScheme)
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("DETOUR")
                    .font(.caption2).bold().kerning(1.2).foregroundStyle(palette.amber)
                Spacer()
                Text("RUNNING")
                    .font(.caption2).bold().kerning(0.7).foregroundStyle(palette.mint)
            }
            Text(model.snapshot.detourOpenCategory)
                .font(.body).foregroundStyle(palette.cloud)
            if !model.snapshot.detourOpenDescription.isEmpty {
                Text(model.snapshot.detourOpenDescription)
                    .font(.caption).foregroundStyle(palette.muted)
            }
            HStack {
                Text(model.snapshot.detourOpenClock)
                    .font(.system(size: 30, weight: .light, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(palette.cloud)
                Spacer()
                Button("Stop") { model.stopOpenDetour() }
                    .buttonStyle(.bordered)
                    .tint(palette.red)
            }
        }
        .dayViewPanel(palette)
    }
}
