import SwiftUI
import DayPlanKit

/// Remaining-time and deadline formatting shared by the panel, the menu bar and the goal card.
enum FocusFormat {
    /// "h:mm:ss" from one hour up, else "m:ss".
    static func clock(_ seconds: TimeInterval) -> String {
        let total = max(0, Int(seconds.rounded(.up)))
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }

    static func deadline(daysLeft n: Int) -> String {
        switch n {
        case 2...: return LF("%lld days left", n)
        case 1: return L("1 day left")
        case 0: return L("Due today")
        case -1: return L("1 day overdue")
        default: return LF("%lld days overdue", -n)
        }
    }
}

extension FocusModel {
    func remaining(at now: Date) -> TimeInterval? {
        current.map { max(0, $0.end.timeIntervalSince(now)) }
    }

    /// "<Area> · <intention> · <m:ss>" for the menu bar, intention omitted when empty.
    func menuLine(at now: Date) -> String? {
        guard let session = current, let remaining = remaining(at: now) else { return nil }
        let intention = session.intention.trimmingCharacters(in: .whitespacesAndNewlines)
        return [areaName(of: session), intention, FocusFormat.clock(remaining)]
            .filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

/// The Focus panel: closure card, then the idle / running / break content.
/// `compact` stacks the controls for the mini window.
struct FocusPanel: View {
    @ObservedObject var focus: FocusModel
    @ObservedObject var plan: PlanModel
    @ObservedObject var todayPlan: TodayPlanModel
    var compact = false

    @Environment(\.colorScheme) private var colorScheme
    private var palette: DayViewPalette { DayViewPalette.current(for: colorScheme) }

    var body: some View {
        VStack(spacing: 12) {
            if let record = focus.pendingClosures.first {
                FocusClosureCard(focus: focus, plan: plan, record: record, compact: compact)
                    .id(record.session.id)
            }
            VStack(alignment: .leading, spacing: 10) {
                if !compact {
                    Text("FOCUS")
                        .font(.caption2).bold().kerning(1.2).foregroundStyle(palette.amber)
                }
                if let session = focus.current {
                    running(session)
                } else {
                    idle
                }
            }
            .dayViewPanel(palette)
        }
    }

    // MARK: Running

    private func running(_ session: FocusSession) -> some View {
        let colorIndex = plan.areas.first { $0.id == session.areaID }?.colorIndex ?? 0
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Circle().fill(palette.areaColor(colorIndex)).frame(width: 10, height: 10)
                Text(plan.areaName(for: session.areaID)).fontWeight(.semibold).lineLimit(1)
                Spacer()
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    Text(FocusFormat.clock(max(0, session.end.timeIntervalSince(context.date))))
                        .font(.system(size: compact ? 18 : 24, weight: .light, design: .rounded))
                        .monospacedDigit()
                        .foregroundStyle(palette.cloud)
                }
            }
            if !session.intention.isEmpty {
                Text(session.intention).foregroundStyle(palette.cloud).lineLimit(compact ? 2 : 4)
            }
            TimelineView(.periodic(from: .now, by: 1)) { context in
                if focus.isPaused(at: context.date) {
                    Text("Paused for a meeting").font(.caption).foregroundStyle(palette.muted)
                }
            }
            HStack {
                Spacer()
                if focus.keepGoing != nil {
                    Button("Keep going (+15 min)") { focus.keepGoingNow() }
                        .tint(palette.mint)
                }
                Button("Stop focus") { focus.stop() }
                    .tint(palette.red)
            }
        }
    }

    // MARK: Idle / break

    @ViewBuilder
    private var idle: some View {
        if let since = focus.breakStartedAt {
            Text(LF("Break since %@", PlanTimeFormat.time(since)))
                .foregroundStyle(.secondary)
        }
        if plan.areas.isEmpty {
            Text("Add an area in Settings to start a focus").foregroundStyle(.secondary)
        } else {
            Picker("Area", selection: areaBinding) {
                ForEach(plan.areas) { area in
                    Text(area.name).tag(area.id)
                }
            }
            TextField("What are you focusing on?", text: $focus.draftIntention)
                .textFieldStyle(.roundedBorder)
            Stepper(
                LF("Duration: %lld min", focus.lastMinutes),
                onIncrement: { focus.setPreferredMinutes(focus.lastMinutes + 5) },
                onDecrement: { focus.setPreferredMinutes(focus.lastMinutes - 5) }
            )
            HStack {
                Spacer()
                Button("Start focus") { focus.startPreferred() }
                    .tint(palette.amber)
                Button("5 min") { focus.quickStart() }
                    .buttonStyle(.bordered)
                    .tint(palette.muted)
            }
        }
        if focus.breakStartedAt == nil {
            Text("Idle").foregroundStyle(.secondary)
        }
    }

    private var areaBinding: Binding<AreaID> {
        Binding(
            get: { focus.effectiveAreaID ?? plan.areas[0].id },
            set: { focus.setPreferredArea($0) }
        )
    }
}

/// The non-blocking closing ritual for the oldest pending record.
struct FocusClosureCard: View {
    @ObservedObject var focus: FocusModel
    @ObservedObject var plan: PlanModel
    let record: FocusRecord
    var compact = false

    @State private var intention: String
    @Environment(\.colorScheme) private var colorScheme
    private var palette: DayViewPalette { DayViewPalette.current(for: colorScheme) }

    init(focus: FocusModel, plan: PlanModel, record: FocusRecord, compact: Bool = false) {
        self.focus = focus
        self.plan = plan
        self.record = record
        self.compact = compact
        _intention = State(initialValue: record.session.intention)
    }

    /// "Close: Writing · 09:00–12:00", with the day ("Mon 16 · 09:00–12:00") for an earlier day.
    private var title: String {
        let start = record.session.start
        var when = PlanTimeFormat.range(start, record.actualEnd)
        if !Calendar.autoupdatingCurrent.isDateInToday(start) {
            when = "\(PlanTimeFormat.shortDay(start)) · \(when)"
        }
        return LF("Close: %@ · %@", plan.areaName(for: record.session.areaID), when)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.subheadline).fontWeight(.semibold).foregroundStyle(palette.cloud).lineLimit(1)
            TextField("What are you focusing on?", text: $intention)
                .textFieldStyle(.roundedBorder)
            HStack(spacing: 8) {
                outcomeButton("Completed", .completed, palette.mint)
                outcomeButton("Progressed", .progressed, palette.amber)
                outcomeButton("Resume later", .toResume, palette.muted)
            }
        }
        .dayViewPanel(palette)
    }

    private func outcomeButton(_ key: LocalizedStringKey, _ outcome: ClosureOutcome, _ tint: Color) -> some View {
        Button(key) { focus.close(record.session.id, outcome: outcome, intention: intention) }
            .buttonStyle(.bordered)
            .tint(tint)
    }
}

/// The active area's goal, shown only while a Focus runs.
struct ActiveAreaGoalCard: View {
    @ObservedObject var focus: FocusModel
    @ObservedObject var plan: PlanModel
    var compact = false

    @Environment(\.colorScheme) private var colorScheme
    private var palette: DayViewPalette { DayViewPalette.current(for: colorScheme) }

    var body: some View {
        if let area = focus.activeArea {
            VStack(alignment: .leading, spacing: 6) {
                Text(area.name)
                    .font(.caption2).bold().kerning(1.2)
                    .foregroundStyle(palette.areaColor(area.colorIndex))
                HStack {
                    Text(area.goalTitle.isEmpty ? L("No goal yet") : area.goalTitle)
                        .foregroundStyle(area.goalTitle.isEmpty ? Color.secondary : Color.primary)
                        .lineLimit(compact ? 1 : 3)
                    Spacer()
                    if let deadline = area.goalDeadline {
                        Text(FocusFormat.deadline(
                            daysLeft: GoalDeadline.daysLeft(from: Date(), to: deadline, calendar: .autoupdatingCurrent)
                        ))
                        .font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .dayViewPanel(palette)
        }
    }
}
