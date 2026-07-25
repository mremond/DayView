import SwiftUI
import DayViewKit

struct HistoryView: View {
    @ObservedObject var model: TodayModel
    @Environment(\.colorScheme) private var colorScheme

    private var palette: DayViewPalette { DayViewPalette.current(for: colorScheme) }
    private var selected: HistoryDaySnapshot? {
        model.snapshot.historyDays.first { $0.dayKey == model.snapshot.selectedHistoryDay }
    }

    var body: some View {
        VStack(spacing: 24) {
            HStack {
                Button {
                    model.closeHistory()
                } label: {
                    Label(selected == nil ? L("Today") : L("History"), systemImage: "chevron.left")
                }
                .keyboardShortcut(.cancelAction)
                Spacer()
                Text(selected.map { Self.longDate($0.dayKey) } ?? L("History"))
                    .font(.title2.weight(.semibold))
                Spacer()
                Color.clear.frame(width: 72)
            }
            if let selected {
                historyDay(selected)
            } else {
                week
            }
        }
        .padding(28)
        .frame(minWidth: 420, minHeight: 560)
        .background(
            RadialGradient(
                gradient: Gradient(colors: [palette.glow, palette.ink]),
                center: .center, startRadius: 0, endRadius: 500
            ).ignoresSafeArea()
        )
    }

    private var week: some View {
        VStack(spacing: 28) {
            HStack(spacing: 10) {
                ForEach(model.snapshot.historyDays, id: \.dayKey) { day in
                    VStack(spacing: 8) {
                        Text(Self.weekday(day.dayKey))
                            .font(.caption).foregroundStyle(day.isToday ? palette.mint : palette.muted)
                        Button {
                            if day.hasData { model.openHistoryDay(day.dayKey) }
                        } label: {
                            if day.hasData {
                                DayRingCanvas(
                                    momentAngleDegrees: day.momentAngleDegrees,
                                    remainingRatio: day.remainingRatio,
                                    isFinished: day.isFinished,
                                    hasStarted: day.hasStarted,
                                    hasGoal: day.hasGoal,
                                    busyArcs: day.busyArcs,
                                    focusArcs: day.focusArcs,
                                    lineWidth: 5,
                                    inset: 9
                                )
                            } else {
                                Circle().fill(palette.overlay.opacity(0.08)).padding(9)
                            }
                        }
                        .buttonStyle(.plain)
                        .disabled(!day.hasData)
                        .frame(width: 54, height: 54)
                        .help(day.hasData ? Self.longDate(day.dayKey) : L("No data"))
                    }
                }
            }
            Text("Select a day to revisit its ring.")
                .foregroundStyle(palette.muted)
            Spacer()
        }
    }

    private func historyDay(_ day: HistoryDaySnapshot) -> some View {
        VStack(spacing: 16) {
            ZStack {
                DayRingCanvas(
                    momentAngleDegrees: day.momentAngleDegrees,
                    remainingRatio: day.remainingRatio,
                    isFinished: day.isFinished,
                    hasStarted: day.hasStarted,
                    hasGoal: day.hasGoal,
                    busyArcs: day.busyArcs,
                    detourBodies: day.detourBodies,
                    focusArcs: day.focusArcs,
                    focusSessionBands: day.focusSessionBands
                )
                VStack(spacing: 4) {
                    Text(day.dayStatus)
                        .font(.system(size: 34, weight: .light, design: .rounded))
                        .monospacedDigit().foregroundStyle(palette.cloud)
                    if !day.netTimeLabel.isEmpty {
                        Text(day.netTimeLabel).font(.caption).foregroundStyle(palette.muted)
                    }
                    if !day.detourTotalLabel.isEmpty {
                        Text(day.detourTotalLabel).font(.caption).foregroundStyle(palette.muted)
                    }
                    if !day.focusTotalLabel.isEmpty {
                        Text(day.focusTotalLabel).font(.caption).foregroundStyle(palette.mint)
                    }
                }
            }
            .frame(maxWidth: 380, maxHeight: 380)
            Text("A read-only view of this day.")
                .font(.caption).foregroundStyle(palette.muted)
        }
        .frame(maxHeight: .infinity)
    }

    private static func weekday(_ epochDay: Int64) -> String {
        let formatter = DateFormatter()
        formatter.locale = .current
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.setLocalizedDateFormatFromTemplate("EEE")
        return formatter.string(from: UpcomingDaysView.date(epochDay)).uppercased()
    }

    private static func longDate(_ epochDay: Int64) -> String {
        let formatter = DateFormatter()
        formatter.locale = .current
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateStyle = .long
        return formatter.string(from: UpcomingDaysView.date(epochDay))
    }
}
