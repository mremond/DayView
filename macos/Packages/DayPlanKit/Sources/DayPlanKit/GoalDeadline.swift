import Foundation

public enum GoalDeadline {
    /// Whole calendar days between the local days of `now` and `deadline`:
    /// negative when overdue, 0 when due today.
    public static func daysLeft(from now: Date, to deadline: Date, calendar: Calendar) -> Int {
        let from = calendar.startOfDay(for: now)
        let to = calendar.startOfDay(for: deadline)
        return calendar.dateComponents([.day], from: from, to: to).day!
    }
}
