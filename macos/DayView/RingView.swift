import SwiftUI
import Foundation
import AppKit
import DayViewKit
import DayPlanKit

struct RingView: View {
    @ObservedObject var model: TodayModel
    @ObservedObject var plan: PlanModel
    @ObservedObject var todayPlan: TodayPlanModel
    @ObservedObject var focus: FocusModel
    @ObservedObject var drift: DriftModel
    @State private var showDetourCapture = false
    @State private var showDetourList = false
    @State private var focusDetail: FocusDetail?

    private struct HoveredArc {
        let label: String
        let position: CGPoint
    }

    @State private var hoveredArc: HoveredArc?

    @Environment(\.colorScheme) private var colorScheme
    private var palette: DayViewPalette { DayViewPalette.current(for: colorScheme) }
    @Environment(\.openWindow) private var openWindow
    @Environment(\.dismissWindow) private var dismissWindow

    var body: some View {
        Group {
            if model.snapshot.destination == "HISTORY" {
                HistoryView(model: model)
            } else {
                ScrollView {
                    VStack(spacing: 24) {
                        HStack {
                            Button {
                                model.openHistory()
                            } label: {
                                Label("History", systemImage: "clock.arrow.circlepath")
                            }
                            .buttonStyle(.borderless)
                            Spacer()
                        }
                        if let areaID = drift.reminderAreaID {
                            DriftBanner(areaName: plan.areaName(for: areaID), onDismiss: drift.dismissReminder)
                        }
                        ringSection
                        UpcomingDaysView(days: model.snapshot.upcomingDays)
                        detourSection
                        MustDosView(model: model)
                        if model.snapshot.detourOpenRunning {
                            OpenDetourBanner(model: model)
                        }
                        FocusPanel(focus: focus, plan: plan, todayPlan: todayPlan)
                        ActiveAreaGoalCard(focus: focus, plan: plan)
                        HeroQuoteView(
                            hasStarted: model.snapshot.hasStarted,
                            isFinished: model.snapshot.isFinished,
                            remainingRatio: model.snapshot.remainingRatio,
                            availablePercent: model.snapshot.availablePercent
                        )
                    }
                    .padding(32)
                }
            }
        }
        .background(
            RadialGradient(
                gradient: Gradient(colors: [palette.glow, palette.ink]),
                center: .center, startRadius: 0, endRadius: 500
            )
            .ignoresSafeArea()
        )
        .sheet(isPresented: $showDetourCapture) { DetourCaptureSheet(model: model, isPresented: $showDetourCapture) }
        .sheet(isPresented: $showDetourList) { DetourListSheet(model: model, isPresented: $showDetourList) }
        .sheet(item: $focusDetail) { detail in
            FocusDetailSheet(detail: detail, onClose: { focusDetail = nil })
        }
    }

    private var ringSection: some View {
        GeometryReader { proxy in
            ZStack {
                DayRingCanvas(
                    momentAngleDegrees: model.snapshot.momentAngleDegrees,
                    remainingRatio: model.snapshot.remainingRatio,
                    isFinished: model.snapshot.isFinished,
                    hasStarted: model.snapshot.hasStarted,
                    hasGoal: focus.activeArea?.hasGoal ?? false,
                    busyArcs: model.snapshot.busyArcs,
                    detourBodies: model.snapshot.detourBodies,
                    swiftFocusBands: focusSpanArcs(now: Date()),
                    deepArcs: deepSpanArcs(now: Date()),
                    planArcs: planArcStyles(now: Date())
                )
                .gesture(SpatialTapGesture(coordinateSpace: .local).onEnded { tap in
                    if let span = focusSpanHit(at: tap.location, in: proxy.size) {
                        focusDetail = detail(for: span, now: Date())
                    }
                })
                .onContinuousHover(coordinateSpace: .local) { phase in
                    switch phase {
                    case .active(let point):
                        hoveredArc = arcHit(at: point, in: proxy.size)
                    case .ended:
                        hoveredArc = nil
                    }
                }
                VStack(spacing: 2) {
                    Text(model.snapshot.dayStatus)
                        .font(.system(size: 40, weight: .light, design: .rounded))
                        .monospacedDigit()
                        .foregroundStyle(palette.cloud)
                    if !model.snapshot.secondsLabel.isEmpty {
                        Text(model.snapshot.secondsLabel)
                            .font(.caption).monospacedDigit().foregroundStyle(palette.muted)
                    }
                    if !model.snapshot.netTimeLabel.isEmpty {
                        Text(model.snapshot.netTimeLabel)
                            .font(.caption).monospacedDigit().foregroundStyle(palette.muted)
                    }
                    if !model.snapshot.detourTotalLabel.isEmpty {
                        Text(model.snapshot.detourTotalLabel)
                            .font(.caption).foregroundStyle(palette.muted)
                    }
                    if !model.snapshot.focusTotalLabel.isEmpty {
                        Text(model.snapshot.focusTotalLabel)
                            .font(.caption).foregroundStyle(palette.mint)
                    }
                }
                if let hover = hoveredArc {
                    Text(hover.label)
                        .font(.caption)
                        .padding(.horizontal, 8).padding(.vertical, 4)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 6))
                        .position(x: hover.position.x + 12, y: hover.position.y - 28)
                        .allowsHitTesting(false)
                }
            }
        }
        .frame(height: 300)
    }

    private var detourSection: some View {
        VStack(spacing: 10) {
            if !model.snapshot.detourSources.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(model.snapshot.detourSources, id: \.label) { source in
                            Button {
                                showDetourList = true
                            } label: {
                                HStack(spacing: 6) {
                                    Circle().fill(palette.detourColor(Int(source.colorIndex))).frame(width: 8, height: 8)
                                    Text(source.label).foregroundStyle(palette.cloud)
                                    Text(source.totalLabel).foregroundStyle(palette.muted)
                                }
                                .font(.caption)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            Button {
                showDetourCapture = true
            } label: {
                Label("Detour", systemImage: "plus")
            }
            .buttonStyle(.bordered)
            .tint(palette.muted)
        }
    }

    // Geometry half of the hit test (angular containment is Kotlin's angularArcIndexAt):
    // pointer -> polar, then whichever lane band the radius sits on. Detour rides an outer
    // lane, busy an inner one; constants come from DayRingCanvas so drawing and hit-testing
    // cannot drift.
    private func arcHit(at point: CGPoint, in size: CGSize) -> HoveredArc? {
        let inset = DayRingCanvas.defaultInset
        let lineWidth = DayRingCanvas.defaultLineWidth
        let side = min(size.width, size.height) - inset * 2
        let radius = max(side / 2, 1)
        let center = CGPoint(x: size.width / 2, y: size.height / 2)
        let dx = point.x - center.x
        let dy = point.y - center.y
        let distance = (dx * dx + dy * dy).squareRoot()
        // y-down atan2 yields the same clockwise-from-3-o'clock convention the arcs use.
        let angle = Double(atan2(dy, dx)) * 180.0 / .pi
        let bandHalf = lineWidth * DayRingCanvas.busyWidthFactor / 2 + 6

        // Detour lane (outer) first.
        let detours = model.snapshot.detourBodies
        let detourRadius = radius + lineWidth * DayRingCanvas.detourRadiusFactor
        if !detours.isEmpty, abs(distance - detourRadius) <= bandHalf {
            let index = Int(TodaySnapshotKt.detourBodyIndexAt(bodies: detours, angleDegrees: angle))
            if index >= 0, index < detours.count {
                return HoveredArc(label: detours[index].hoverLabel, position: point)
            }
        }

        // Swift Focus sessions share the main lane.
        if abs(distance - radius) <= lineWidth / 2 + 6, let span = focusSpan(atAngle: angle, now: Date()) {
            return HoveredArc(label: focusHoverLabel(for: span), position: point)
        }

        // Busy lane (inner).
        let arcs = model.snapshot.busyArcs
        let busyRadius = radius - lineWidth * DayRingCanvas.busyRadiusFactor
        if !arcs.isEmpty, abs(distance - busyRadius) <= bandHalf {
            let index = Int(TodaySnapshotKt.busyArcIndexAt(arcs: arcs, angleDegrees: angle))
            if index >= 0, index < arcs.count {
                return HoveredArc(label: arcs[index].hoverLabel, position: point)
            }
        }

        // Plan lane (innermost).
        let planRadius = radius - lineWidth * DayRingCanvas.planRadiusFactor
        let planBandHalf = lineWidth * DayRingCanvas.planWidthFactor / 2 + 6
        if abs(distance - planRadius) <= planBandHalf, let label = planHoverLabel(at: angle) {
            return HoveredArc(label: label, position: point)
        }
        return nil
    }

    // MARK: Plan lane

    /// Today's day window in wall-clock time, so it stays aligned with the busy arcs on DST days.
    private var planWindow: DateInterval {
        let snap = model.snapshot
        return PlanRing.window(
            day: todayPlan.state.day, startMinute: Int(snap.startMinutes), endMinute: Int(snap.endMinutes),
            calendar: .autoupdatingCurrent
        )
    }

    private var visiblePlan: [FittedBlock] {
        todayPlan.state.fitted.filter { !$0.isDropped }
    }

    private func planArcs() -> [PlanArc] {
        let window = planWindow
        return PlanRing.arcs(visiblePlan, windowStart: window.start, windowEnd: window.end)
    }

    private func planArcStyles(now: Date) -> [PlanArcStyle] {
        let fitted = visiblePlan
        guard !fitted.isEmpty else { return [] }
        let areasByID = Dictionary(plan.areas.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        return planArcs().compactMap { arc in
            guard let area = areasByID[arc.areaID] else { return nil }
            let block = fitted.first { $0.block.id == arc.blockID }
            let running = block.map { $0.isRunning(at: now) && !$0.isPaused(at: now) } ?? false
            return PlanArcStyle(arc: arc, colorIndex: area.colorIndex, isRunning: running)
        }
    }

    private func planHoverLabel(at angle: Double) -> String? {
        let arcs = planArcs()
        guard let index = PlanRing.arcIndex(at: angle, in: arcs),
              let fitted = visiblePlan.first(where: { $0.block.id == arcs[index].blockID }),
              let start = fitted.start, let end = fitted.end,
              let area = plan.areas.first(where: { $0.id == arcs[index].areaID }) else { return nil }
        var label = "\(area.name) · \(PlanTimeFormat.range(start, end))"
        let intention = fitted.block.intention.trimmingCharacters(in: .whitespacesAndNewlines)
        if !intention.isEmpty { label += " · \(intention)" }
        return label
    }

    // MARK: Focus lane

    private typealias FocusSpan = (id: UUID, areaID: AreaID, interval: DateInterval)

    /// Today's Focus: closed records, pending closures and the running session (start to now).
    private func focusSpans(now: Date) -> [FocusSpan] {
        let day = todayPlan.state.day
        var spans: [FocusSpan] = plan.closedFocus(on: day).map {
            ($0.id, $0.areaID, DateInterval(start: $0.start, end: max($0.end, $0.start)))
        }
        for record in focus.pendingClosures {
            let s = record.session
            spans.append((s.id, s.areaID, DateInterval(start: s.start, end: max(record.actualEnd, s.start))))
        }
        if let s = focus.current {
            spans.append((s.id, s.areaID, DateInterval(start: s.start, end: max(now, s.start))))
        }
        return spans
    }

    private func focusSpanArcs(now: Date) -> [SpanArc] {
        let window = planWindow
        return PlanRing.spanArcs(focusSpans(now: now), windowStart: window.start, windowEnd: window.end)
    }

    private func focusSpan(atAngle angle: Double, now: Date) -> FocusSpan? {
        let spans = focusSpans(now: now)
        let window = planWindow
        let arcs = PlanRing.spanArcs(spans, windowStart: window.start, windowEnd: window.end)
        guard let index = PlanRing.spanIndex(at: angle, in: arcs) else { return nil }
        return spans.first { $0.id == arcs[index].id }
    }

    private func focusHoverLabel(for span: FocusSpan) -> String {
        "\(plan.areaName(for: span.areaID)) \u{00B7} \(PlanTimeFormat.range(span.interval.start, span.interval.end))"
    }

    /// The Focus session whose band sits under `point`, same lane geometry as the hover.
    private func focusSpanHit(at point: CGPoint, in size: CGSize) -> FocusSpan? {
        let lineWidth = DayRingCanvas.defaultLineWidth
        let radius = max((min(size.width, size.height) - DayRingCanvas.defaultInset * 2) / 2, 1)
        let dx = point.x - size.width / 2
        let dy = point.y - size.height / 2
        let distance = (dx * dx + dy * dy).squareRoot()
        guard abs(distance - radius) <= lineWidth / 2 + 6 else { return nil }
        return focusSpan(atAngle: Double(atan2(dy, dx)) * 180.0 / .pi, now: Date())
    }

    /// Bright deep-focus arcs: today's stored stretches plus the running Focus's live ones.
    private func deepSpanArcs(now: Date) -> [SpanArc] {
        var spans: [FocusSpan] = plan.focusMetrics(on: todayPlan.state.day).values.flatMap { metrics in
            metrics.deepStretches.map { (metrics.sessionID, metrics.areaID, $0) }
        }
        if let s = focus.current {
            spans += drift.liveDeepStretches(at: now).map { (s.id, s.areaID, $0) }
        }
        let window = planWindow
        return PlanRing.spanArcs(spans, windowStart: window.start, windowEnd: window.end)
    }

    // MARK: Focus detail

    private func detail(for span: FocusSpan, now: Date) -> FocusDetail? {
        let day = todayPlan.state.day
        let metrics = plan.focusMetrics(on: day)[span.id]
        let colorIndex = plan.areas.first { $0.id == span.areaID }?.colorIndex ?? 0
        if let closed = plan.closedFocus(on: day).first(where: { $0.id == span.id }) {
            return FocusDetail(
                id: closed.id, areaName: plan.areaName(for: closed.areaID), colorIndex: closed.colorIndex,
                intention: closed.intention, start: closed.start, end: closed.end,
                engaged: metrics?.engaged ?? closed.focused, deep: metrics?.deep, outcome: Self.outcomeLabel(closed.outcome)
            )
        }
        if let record = focus.pendingClosures.first(where: { $0.session.id == span.id }) {
            let s = record.session
            return FocusDetail(
                id: s.id, areaName: plan.areaName(for: s.areaID), colorIndex: colorIndex, intention: s.intention,
                start: s.start, end: record.actualEnd, engaged: metrics?.engaged ?? record.focused,
                deep: metrics?.deep, outcome: L("Not closed yet")
            )
        }
        guard let s = focus.current, s.id == span.id else { return nil }
        let end = max(now, s.start)
        let engaged = EngagedTime.compute(
            focused: focus.focusedSoFar(at: now), window: DateInterval(start: s.start, end: end),
            detours: model.detourIntervals
        )
        let deep = drift.liveDeepTotal(at: now)
        return FocusDetail(
            id: s.id, areaName: plan.areaName(for: s.areaID), colorIndex: colorIndex, intention: s.intention,
            start: s.start, end: end, engaged: engaged, deep: deep, outcome: L("In progress")
        )
    }

    private static func outcomeLabel(_ outcome: ClosureOutcome) -> String {
        switch outcome {
        case .completed: L("Completed")
        case .progressed: L("Progressed")
        case .toResume: L("Resume later")
        }
    }
}
