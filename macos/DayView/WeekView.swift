import SwiftUI
import Combine
import DayPlanKit
import DayPlanStore

/// "This week": planned versus Focus time per area from Monday to today, and the two signals
/// for each day. A plain list; recomputed on appear and every minute.
struct WeekView: View {
    @ObservedObject var plan: PlanModel
    @ObservedObject var focus: FocusModel
    @Environment(\.colorScheme) private var scheme
    @State private var summary: WeekSummary?

    private let calendar = Calendar.autoupdatingCurrent
    private let minute = Timer.publish(every: 60, on: .main, in: .common).autoconnect()

    private var palette: DayViewPalette { DayViewPalette.current(for: scheme) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if let summary {
                    Text(header(summary.days)).font(.title3.bold())
                    if summary.areas.isEmpty {
                        Text(L("Nothing yet this week")).foregroundStyle(.secondary)
                    } else {
                        VStack(alignment: .leading, spacing: 8) {
                            ForEach(summary.areas, id: \.areaID) { areaRow($0) }
                        }
                    }
                    Divider()
                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(summary.signals, id: \.day) { dayRow($0) }
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(20)
        }
        .frame(minWidth: 380, minHeight: 300)
        .onAppear { refresh() }
        .onReceive(minute) { _ in refresh() }
    }

    // MARK: Rows

    private func areaRow(_ line: AreaWeekLine) -> some View {
        let area = plan.areas.first { $0.id == line.areaID }
        return HStack(spacing: 8) {
            Circle().fill(palette.areaColor(area?.colorIndex ?? 0)).frame(width: 10, height: 10)
            Text(plan.areaName(for: line.areaID)).fontWeight(.semibold)
            Spacer()
            Text(LF("Planned %@ · Focus %@", Self.hoursMinutes(line.planned), Self.hoursMinutes(line.focused)))
                .foregroundStyle(.secondary)
                .monospacedDigit()
        }
    }

    private func dayRow(_ signals: DaySignals) -> some View {
        HStack(spacing: 8) {
            Text(PlanTimeFormat.shortDay(signals.day.start(in: calendar))).fontWeight(.semibold)
            Text(reviewText(signals.review)).foregroundStyle(.secondary)
            Spacer()
            Text(ignoredText(signals.ignoredCues)).foregroundStyle(.secondary)
        }
    }

    private func reviewText(_ state: ReviewState) -> String {
        switch state {
        case .reviewed: return L("Plan reviewed")
        case .autoAccepted: return L("Auto-accepted")
        case .noPlan: return L("No plan")
        }
    }

    private func ignoredText(_ count: Int) -> String {
        count == 1 ? L("1 cue ignored") : LF("%lld cues ignored", count)
    }

    // MARK: Formatting

    /// "Monday 16 – Wednesday 18 November"; a single-day week shows one date.
    private func header(_ days: [LocalDay]) -> String {
        guard let first = days.first, let last = days.last else { return "" }
        let end = last.start(in: calendar)
        let endText = end.formatted(.dateTime.weekday(.wide).day().month(.wide))
        guard first != last else { return endText }
        let start = first.start(in: calendar)
        let startText = start.formatted(.dateTime.weekday(.wide).day())
        return "\(startText) – \(endText)"
    }

    /// "h:mm", e.g. "4:00" or "0:50".
    static func hoursMinutes(_ seconds: TimeInterval) -> String {
        let minutes = Int((max(0, seconds) / 60).rounded())
        return String(format: "%d:%02d", minutes / 60, minutes % 60)
    }

    // MARK: Data

    private func refresh() {
        let now = Date()
        let today = LocalDay(containing: now, calendar: calendar)
        let back = today.weekdayISO(in: calendar) - 1
        guard let mondayDate = calendar.date(byAdding: .day, value: -back, to: today.start(in: calendar)) else { return }
        let monday = LocalDay(containing: mondayDate, calendar: calendar)
        var times = plan.closedFocus(from: monday, through: today).map {
            FocusTime(day: $0.day, areaID: $0.areaID, seconds: $0.focused)
        }
        times += focus.pendingClosures.map {
            FocusTime(day: LocalDay(containing: $0.session.start, calendar: calendar),
                      areaID: $0.session.areaID, seconds: $0.focused)
        }
        if let session = focus.current {
            times.append(FocusTime(
                day: LocalDay(containing: session.start, calendar: calendar),
                areaID: session.areaID, seconds: max(0, now.timeIntervalSince(session.start))
            ))
        }
        summary = WeekSummary.compute(
            today: today, calendar: calendar,
            accepted: plan.acceptedPlans(from: monday, through: today), focus: times,
            ignored: plan.ignoredCues(from: monday, through: today), areaOrder: plan.areas.map(\.id)
        )
    }
}
