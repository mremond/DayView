import Foundation

/// Applies a `KeepGoingOffer` to the plan.
public enum KeepGoing {
    /// The new plan, and for a reopened Focus the fitted start of the shifted block
    /// (nil when the block was dropped or is missing, or for an extension).
    public static func apply(
        _ offer: KeepGoingOffer, to plan: DayPlan, meetings: [DateInterval], calendar: Calendar
    ) -> (plan: DayPlan, reopenUntil: Date?) {
        switch offer {
        case let .extendBlock(id):
            return (plan.keepingGoing(blockID: id, by: FocusEngine.keepGoingExtension, calendar: calendar), nil)
        case let .reopenInterrupted(_, id):
            let shifted = plan.shiftingFrom(blockID: id, by: FocusEngine.keepGoingExtension, calendar: calendar)
            let start = PlanFitter.fit(shifted, meetings: meetings)
                .first { $0.block.id == id && !$0.isDropped }?.start
            return (shifted, start)
        }
    }
}
