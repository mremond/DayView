import SwiftUI

struct HeroQuoteView: View {
    let hasStarted: Bool
    let isFinished: Bool
    let remainingRatio: Double
    let availablePercent: Int64

    @State private var selection = Int.random(in: 0..<12)
    @State private var revealSource = false
    @Environment(\.colorScheme) private var colorScheme

    private var palette: DayViewPalette { DayViewPalette.current(for: colorScheme) }

    private var quote: (String, String) {
        let pool: [(String, String)]
        if !hasStarted {
            pool = [
                ("A quiet beginning leaves room for what matters.", ""),
                ("Start with the one thing worth carrying through the day.", "")
            ]
        } else if isFinished {
            pool = [
                ("The day is complete. Let what was enough be enough.", ""),
                ("Close the day gently; tomorrow does not need to begin tonight.", "")
            ]
        } else if remainingRatio < 0.2 {
            pool = [
                ("Protect the final stretch for what matters most.", ""),
                ("A small, deliberate finish is still a finish.", "")
            ]
        } else {
            pool = [
                ("One thing at a time.", ""),
                ("Attention is the beginning of devotion.", "Mary Oliver"),
                ("What you pay attention to grows.", "")
            ]
        }
        return pool[selection % pool.count]
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L(quote.0))
                .font(.title3.weight(.medium))
                .foregroundStyle(palette.cloud)
            if revealSource && !quote.1.isEmpty {
                Text(quote.1)
                    .font(.caption).foregroundStyle(palette.muted)
            }
            HStack(spacing: 8) {
                Circle()
                    .fill(isFinished ? palette.red : palette.mint)
                    .frame(width: 8, height: 8)
                Text(LF("%lld%% of the day available", availablePercent))
                    .font(.caption).foregroundStyle(palette.muted)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { revealSource.toggle() }
        .onHover { revealSource = $0 }
    }
}
