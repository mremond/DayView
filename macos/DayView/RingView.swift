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
    @State private var showDetourCapture = false
    @State private var showDetourList = false
    @State private var selectedFocusSession: FocusSessionSnapshot?
    @State private var showFocusSessionDetail = false

    private struct HoveredArc {
        let label: String
        let position: CGPoint
        let focusSessionIndex: Int?
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
        .sheet(isPresented: $showFocusSessionDetail) {
            if let selectedFocusSession {
                FocusSessionDetailSheet(session: selectedFocusSession, isPresented: $showFocusSessionDetail)
            }
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
                    hasGoal: focus.activeArea.map { !$0.goalTitle.isEmpty || $0.goalDeadline != nil } ?? false,
                    busyArcs: model.snapshot.busyArcs,
                    detourBodies: model.snapshot.detourBodies,
                    focusArcs: model.snapshot.focusArcs,
                    focusSessionBands: model.snapshot.focusSessionBands,
                    planArcs: planArcStyles(now: Date())
                )
                .onContinuousHover(coordinateSpace: .local) { phase in
                    switch phase {
                    case .active(let point):
                        hoveredArc = arcHit(at: point, in: proxy.size)
                    case .ended:
                        hoveredArc = nil
                    }
                }
                .onTapGesture {
                    guard let index = hoveredArc?.focusSessionIndex,
                          index >= 0,
                          index < model.snapshot.focusSessions.count else { return }
                    selectedFocusSession = model.snapshot.focusSessions[index]
                    showFocusSessionDetail = true
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
                return HoveredArc(label: detours[index].hoverLabel, position: point, focusSessionIndex: nil)
            }
        }

        // Closed Focus sessions share the main lane. Hover gives a compact hint; clicking
        // opens the full engaged/deep-focus detail sheet.
        let sessions = model.snapshot.focusSessions
        if !sessions.isEmpty, abs(distance - radius) <= lineWidth / 2 + 6 {
            if let index = focusSessionIndex(at: angle, sessions: sessions) {
                let session = sessions[index]
                let title = session.intention.isEmpty ? L("Untitled focus") : session.intention
                return HoveredArc(
                    label: "\(title) · \(session.timeRangeLabel)",
                    position: point,
                    focusSessionIndex: index
                )
            }
        }

        // Busy lane (inner).
        let arcs = model.snapshot.busyArcs
        let busyRadius = radius - lineWidth * DayRingCanvas.busyRadiusFactor
        if !arcs.isEmpty, abs(distance - busyRadius) <= bandHalf {
            let index = Int(TodaySnapshotKt.busyArcIndexAt(arcs: arcs, angleDegrees: angle))
            if index >= 0, index < arcs.count {
                return HoveredArc(label: arcs[index].hoverLabel, position: point, focusSessionIndex: nil)
            }
        }

        // Plan lane (innermost).
        let planRadius = radius - lineWidth * DayRingCanvas.planRadiusFactor
        let planBandHalf = lineWidth * DayRingCanvas.planWidthFactor / 2 + 6
        if abs(distance - planRadius) <= planBandHalf, let label = planHoverLabel(at: angle) {
            return HoveredArc(label: label, position: point, focusSessionIndex: nil)
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

    private func focusSessionIndex(at angle: Double, sessions: [FocusSessionSnapshot]) -> Int? {
        let probe = normalizedDegrees(angle)
        var bestIndex: Int?
        var bestDistance = Double.greatestFiniteMagnitude
        for (index, session) in sessions.enumerated() {
            let start = normalizedDegrees(session.startAngleDegrees)
            let offset = normalizedDegrees(probe - start)
            let distance = offset <= session.sweepDegrees
                ? 0
                : min(offset - session.sweepDegrees, 360 - offset)
            if distance < bestDistance {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestDistance <= 4 ? bestIndex : nil
    }

    private func normalizedDegrees(_ value: Double) -> Double {
        let remainder = value.truncatingRemainder(dividingBy: 360)
        return remainder < 0 ? remainder + 360 : remainder
    }
}
