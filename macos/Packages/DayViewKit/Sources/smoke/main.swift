import DayViewKit
import Foundation

let nowMillis = Int64(Date().timeIntervalSince1970 * 1000)
let snapshot = DayViewCore.shared.dayProgress(
    nowEpochMillis: nowMillis,
    startMinutes: 540,
    endMinutes: 1080
)

print("remainingSeconds=\(snapshot.remainingSeconds)")
print("remainingRatio=\(snapshot.remainingRatio)")
print("momentAngleDegrees=\(snapshot.momentAngleDegrees)")
print("isFinished=\(snapshot.isFinished)")
print("remaining=\(snapshot.remainingHours)h \(snapshot.remainingMinutes)m")
