import SwiftUI

/// What the detail sheet shows for one Focus session, captured when its band is clicked.
struct FocusDetail: Identifiable {
    let id: UUID
    let areaName: String
    let colorIndex: Int
    let intention: String
    let start: Date
    let end: Date
    let engaged: TimeInterval
    /// Nil when deep focus is not measured for the area.
    let deep: TimeInterval?
    let outcome: String
}

/// One Focus session: area, intention, time range, engaged and deep-focus time, and outcome.
struct FocusDetailSheet: View {
    let detail: FocusDetail
    let onClose: () -> Void
    @Environment(\.colorScheme) private var scheme

    private var palette: DayViewPalette { DayViewPalette.current(for: scheme) }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Circle().fill(palette.areaColor(detail.colorIndex)).frame(width: 10, height: 10)
                Text(detail.areaName).font(.headline)
            }
            let intention = detail.intention.trimmingCharacters(in: .whitespacesAndNewlines)
            if !intention.isEmpty { Text(intention).foregroundStyle(palette.cloud) }
            Text(PlanTimeFormat.range(detail.start, detail.end)).foregroundStyle(palette.muted)
            Text(LF("Engaged %@", WeekView.hoursMinutes(detail.engaged))).monospacedDigit()
            if let deep = detail.deep {
                Text(LF("Deep focus %@", WeekView.hoursMinutes(deep))).monospacedDigit()
            }
            Text(detail.outcome).foregroundStyle(palette.muted)
            HStack {
                Spacer()
                Button(L("Close"), action: onClose).keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 320)
    }
}
