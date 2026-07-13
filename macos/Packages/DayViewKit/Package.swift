// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "DayViewKit",
    platforms: [.macOS(.v13)],
    products: [
        .library(name: "DayViewKit", targets: ["DayViewKit"]),
    ],
    targets: [
        .binaryTarget(
            name: "DayViewKit",
            path: "DayViewKit.xcframework"
        ),
        .executableTarget(
            name: "smoke",
            dependencies: ["DayViewKit"],
            path: "Sources/smoke"
        ),
    ]
)
