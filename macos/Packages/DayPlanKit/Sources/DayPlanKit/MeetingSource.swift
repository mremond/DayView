import Foundation

public protocol MeetingSource {
    func meetings(on day: LocalDay) -> [DateInterval]
}
