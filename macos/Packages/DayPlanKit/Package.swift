// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "DayPlanKit",
    platforms: [.macOS(.v15), .iOS(.v18)],
    products: [
        .library(name: "DayPlanKit", targets: ["DayPlanKit"]),
        .library(name: "DayPlanStore", targets: ["DayPlanStore"])
    ],
    targets: [
        .target(name: "DayPlanKit"),
        .target(name: "DayPlanStore", dependencies: ["DayPlanKit"]),
        .testTarget(name: "DayPlanKitTests", dependencies: ["DayPlanKit"]),
        .testTarget(name: "DayPlanStoreTests", dependencies: ["DayPlanStore"])
    ]
)
