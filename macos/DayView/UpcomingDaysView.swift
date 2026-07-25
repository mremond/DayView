import SwiftUI
import DayViewKit

struct UpcomingDaysView: View {
    let days: [UpcomingDaySnapshot]

    @Environment(\.colorScheme) private var colorScheme
    private var palette: DayViewPalette { DayViewPalette.current(for: colorScheme) }

    var body: some View {
        if !days.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                Text("NEXT THREE DAYS")
                    .font(.caption2).bold().kerning(1.2).foregroundStyle(palette.mint)
                HStack(spacing: 10) {
                    ForEach(days, id: \.epochDay) { day in
                        VStack(spacing: 8) {
                            Text(Self.shortDate(day.epochDay))
                                .font(.caption).foregroundStyle(palette.muted)
                            ZStack {
                                Circle().stroke(palette.overlay.opacity(0.12), lineWidth: 7)
                                Circle()
                                    .trim(from: 0, to: ratio(day))
                                    .stroke(palette.mint, style: StrokeStyle(lineWidth: 7, lineCap: .round))
                                    .rotationEffect(.degrees(-90))
                                Text(Self.duration(day.netMinutes))
                                    .font(.caption2).monospacedDigit().foregroundStyle(palette.cloud)
                            }
                            .frame(width: 66, height: 66)
                        }
                        .frame(maxWidth: .infinity)
                    }
                }
            }
            .dayViewPanel(palette)
        }
    }

    private func ratio(_ day: UpcomingDaySnapshot) -> CGFloat {
        guard day.windowMinutes > 0 else { return 0 }
        return CGFloat(Double(day.netMinutes) / Double(day.windowMinutes))
    }

    private static func duration(_ minutes: Int64) -> String {
        let hours = minutes / 60
        let mins = minutes % 60
        return hours > 0 ? "\(hours)h\(String(format: "%02d", mins))" : "\(mins)m"
    }

    static func date(_ epochDay: Int64) -> Date {
        var calendar = Calendar(identifier: .iso8601)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let origin = calendar.date(from: DateComponents(year: 1970, month: 1, day: 1))!
        return calendar.date(byAdding: .day, value: Int(epochDay), to: origin)!
    }

    static func shortDate(_ epochDay: Int64) -> String {
        let formatter = DateFormatter()
        formatter.locale = .current
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.setLocalizedDateFormatFromTemplate("EEE d")
        return formatter.string(from: date(epochDay))
    }
}
