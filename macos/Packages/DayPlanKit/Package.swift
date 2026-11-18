// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "DayPlanKit",
    platforms: [.macOS(.v15), .iOS(.v18)],
    products: [
        .library(name: "DayPlanKit", targets: ["DayPlanKit"])
    ],
    targets: [
        .target(name: "DayPlanKit"),
        .testTarget(name: "DayPlanKitTests", dependencies: ["DayPlanKit"])
    ]
)
