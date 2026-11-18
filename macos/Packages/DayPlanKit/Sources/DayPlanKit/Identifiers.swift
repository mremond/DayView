import Foundation

public struct AreaID: Sendable, Hashable, Codable {
    public let raw: UUID
    public init(_ raw: UUID = UUID()) { self.raw = raw }
}

public struct BlockID: Sendable, Hashable, Codable {
    public let raw: UUID
    public init(_ raw: UUID = UUID()) { self.raw = raw }
}
