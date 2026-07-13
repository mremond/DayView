# macOS Native Swift UI — Walking Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the full Path-B pipeline — extract `DayProgress` into a Compose-free multi-target `:core` module, ship it to macOS as a Kotlin/Native XCFramework, and render a live progress ring from it in a native SwiftUI app.

**Architecture:** A new `:core` KMP library (targets: Android via `com.android.library`, JVM for Linux/Android-shared logic, `macosArm64`/`macosX64` for the framework) holds the moved `DayProgress` plus a primitives-only Swift-facing facade. Gradle assembles an XCFramework consumed by a local Swift Package (binary target). An XcodeGen-generated SwiftUI app calls the facade on a 1 Hz timer and draws the ring in a `Canvas`.

**Tech Stack:** Kotlin Multiplatform 2.4.0, kotlinx-datetime 0.8.0, AGP 9.2.1, Gradle, ktlint 14.2.0, Swift 5.9 / SwiftUI, XcodeGen, Xcode.

## Global Constraints

- JDK 21 (`jvmToolchain(21)`); Kotlin `2.4.0`; AGP `9.2.1`; kotlinx-datetime `0.8.0`; ktlint `14.2.0`.
- `:core` Android config: `namespace = "fr.dayview.core"`, `compileSdk = 37`, `minSdk = 24`.
- `:core` applies `com.android.library` — **never** `com.android.application` (that is issue #18's forbidden combo).
- Moved Kotlin code keeps package `fr.dayview.app` so existing call sites in `:composeApp` need no import changes.
- The Kotlin/Native framework module is named **`DayViewKit`** (imported in Swift as `import DayViewKit`); the Kotlin facade object is `DayViewCore`. This refines the spec's `DayViewCore` framework name to avoid a Swift module/type name collision.
- Commit messages: English, imperative, describe the change only. **Never** reference Claude/Anthropic/AI or add Co-Authored-By trailers.
- Full gate before considering a task done where noted: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`.
- Tasks 3–5 require a macOS host with Xcode and XcodeGen (`brew install xcodegen`).

---

## File Structure

- `settings.gradle.kts` — add `include(":core")`.
- `gradle/libs.versions.toml` — add `androidLibrary` plugin alias.
- `core/build.gradle.kts` — new KMP library build script.
- `core/src/commonMain/kotlin/fr/dayview/app/DayProgress.kt` — moved from `:composeApp`.
- `core/src/commonMain/kotlin/fr/dayview/app/DayViewCore.kt` — new facade + `DayProgressSnapshot`.
- `core/src/commonTest/kotlin/fr/dayview/app/DayProgressTest.kt` — moved from `:composeApp`.
- `core/src/commonTest/kotlin/fr/dayview/app/DayViewCoreTest.kt` — new facade test.
- `composeApp/build.gradle.kts` — add `implementation(project(":core"))`.
- `macos/Packages/DayViewKit/Package.swift` — SPM package wrapping the XCFramework as a binary target + a headless smoke executable.
- `macos/Packages/DayViewKit/Sources/smoke/main.swift` — interop smoke check.
- `macos/project.yml` — XcodeGen config for the SwiftUI app.
- `macos/DayView/DayViewApp.swift` — app entry point.
- `macos/DayView/RingView.swift` — SwiftUI ring + timer.
- `.gitignore` — ignore synced xcframework, generated `.xcodeproj`, Swift build dirs.

---

## Task 1: Extract `DayProgress` into a new `:core` KMP module

Creates `:core` with all four targets and moves `DayProgress` (and its test) into it, wiring `:composeApp` to depend on it. Deliverable: Android + Linux/desktop build and tests stay green off the shared module, and `:core` compiles for macOS native.

**Files:**
- Modify: `gradle/libs.versions.toml` (plugins section)
- Modify: `settings.gradle.kts`
- Create: `core/build.gradle.kts`
- Move: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayProgress.kt` → `core/src/commonMain/kotlin/fr/dayview/app/DayProgress.kt`
- Move: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayProgressTest.kt` → `core/src/commonTest/kotlin/fr/dayview/app/DayProgressTest.kt`
- Modify: `composeApp/build.gradle.kts` (commonMain dependencies)

**Interfaces:**
- Produces: module `:core` exposing `fr.dayview.app.calculateDayProgress(now: kotlin.time.Instant, startMinutesOfDay: Int, endMinutesOfDay: Int, timeZone: TimeZone = …): DayProgress`, `fr.dayview.app.currentMomentAngleDegrees(remainingRatio: Float): Float`, and the `DayProgress` data class — unchanged signatures from the current file.

- [ ] **Step 1: Add the `com.android.library` plugin alias**

In `gradle/libs.versions.toml`, under `[plugins]`, add after the `androidApplication` line:

```toml
androidLibrary = { id = "com.android.library", version.ref = "agp" }
```

- [ ] **Step 2: Include `:core` in the build**

In `settings.gradle.kts`, replace the final line `include(":composeApp")` with:

```kotlin
include(":composeApp")
include(":core")
```

- [ ] **Step 3: Create `core/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)

    androidTarget()
    jvm()
    macosArm64()
    macosX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.datetime)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

android {
    namespace = "fr.dayview.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }
}
```

- [ ] **Step 4: Move `DayProgress.kt` into `:core`**

Run:

```bash
git mv composeApp/src/commonMain/kotlin/fr/dayview/app/DayProgress.kt \
       core/src/commonMain/kotlin/fr/dayview/app/DayProgress.kt
```

Do **not** edit the file contents — the package stays `fr.dayview.app`.

- [ ] **Step 5: Move `DayProgressTest.kt` into `:core`**

Run:

```bash
git mv composeApp/src/commonTest/kotlin/fr/dayview/app/DayProgressTest.kt \
       core/src/commonTest/kotlin/fr/dayview/app/DayProgressTest.kt
```

- [ ] **Step 6: Depend on `:core` from `:composeApp`**

In `composeApp/build.gradle.kts`, inside `val commonMain by getting { dependencies { … } }`, add as the first dependency line:

```kotlin
                implementation(project(":core"))
```

- [ ] **Step 7: Verify macOS native compiles**

Run: `./gradlew :core:compileKotlinMacosArm64`
Expected: BUILD SUCCESSFUL (proves `DayProgress` + kotlinx-datetime compile for Kotlin/Native).

If AGP reports a missing Android manifest for `:core`, create `core/src/androidMain/AndroidManifest.xml` with exactly `<manifest />` and re-run.

- [ ] **Step 8: Verify the moved test runs in `:core`**

Run: `./gradlew :core:jvmTest`
Expected: PASS — `DayProgressTest` executes from its new home.

- [ ] **Step 9: Verify Android + Linux + lint stay green**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL — `:composeApp` consumes `DayProgress` from `:core` with no call-site changes.

- [ ] **Step 10: Commit**

```bash
git add gradle/libs.versions.toml settings.gradle.kts core composeApp/build.gradle.kts
git commit -m "build: extract DayProgress into a shared :core KMP module"
```

---

## Task 2: Add the Swift-facing facade

Adds the primitives-only `DayViewCore` facade and `DayProgressSnapshot`, the permanent boundary Swift calls through. Deliverable: facade compiles on all targets and a test pins its flattening against `calculateDayProgress`.

**Files:**
- Create: `core/src/commonMain/kotlin/fr/dayview/app/DayViewCore.kt`
- Create: `core/src/commonTest/kotlin/fr/dayview/app/DayViewCoreTest.kt`

**Interfaces:**
- Consumes: `calculateDayProgress`, `currentMomentAngleDegrees`, `DayProgress` from Task 1.
- Produces: `fr.dayview.app.DayViewCore.dayProgress(nowEpochMillis: Long, startMinutes: Int, endMinutes: Int): DayProgressSnapshot` and `data class DayProgressSnapshot(remainingSeconds: Long, remainingRatio: Double, momentAngleDegrees: Double, isFinished: Boolean, remainingHours: Long, remainingMinutes: Long)`.

- [ ] **Step 1: Write the failing facade test**

Create `core/src/commonTest/kotlin/fr/dayview/app/DayViewCoreTest.kt`:

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class DayViewCoreTest {
    @Test
    fun snapshotFlattensCalculateDayProgress() {
        val nowMillis = 1_700_000_000_000L
        val start = 540 // 09:00
        val end = 1080 // 18:00

        val snapshot = DayViewCore.dayProgress(nowMillis, start, end)
        val expected = calculateDayProgress(
            now = Instant.fromEpochMilliseconds(nowMillis),
            startMinutesOfDay = start,
            endMinutesOfDay = end,
        )

        assertEquals(expected.remaining.inWholeSeconds, snapshot.remainingSeconds)
        assertEquals(expected.remainingRatio.toDouble(), snapshot.remainingRatio)
        assertEquals(
            currentMomentAngleDegrees(expected.remainingRatio).toDouble(),
            snapshot.momentAngleDegrees,
        )
        assertEquals(expected.isFinished, snapshot.isFinished)
        assertEquals(expected.remainingHours, snapshot.remainingHours)
        assertEquals(expected.remainingMinutes, snapshot.remainingMinutes)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.DayViewCoreTest"`
Expected: FAIL — `DayViewCore` / `DayProgressSnapshot` unresolved.

- [ ] **Step 3: Implement the facade**

Create `core/src/commonMain/kotlin/fr/dayview/app/DayViewCore.kt`:

```kotlin
package fr.dayview.app

import kotlin.time.Instant

/**
 * Swift-facing snapshot of [DayProgress] using primitives only, so the value
 * bridges cleanly to Objective-C/Swift (no Kotlin value classes or Duration).
 */
data class DayProgressSnapshot(
    val remainingSeconds: Long,
    val remainingRatio: Double,
    val momentAngleDegrees: Double,
    val isFinished: Boolean,
    val remainingHours: Long,
    val remainingMinutes: Long,
)

/**
 * Stable entry point for native (macOS/Swift) callers. Takes epoch milliseconds
 * instead of [Instant] and returns a flattened [DayProgressSnapshot].
 */
object DayViewCore {
    fun dayProgress(
        nowEpochMillis: Long,
        startMinutes: Int,
        endMinutes: Int,
    ): DayProgressSnapshot {
        val progress = calculateDayProgress(
            now = Instant.fromEpochMilliseconds(nowEpochMillis),
            startMinutesOfDay = startMinutes,
            endMinutesOfDay = endMinutes,
        )
        return DayProgressSnapshot(
            remainingSeconds = progress.remaining.inWholeSeconds,
            remainingRatio = progress.remainingRatio.toDouble(),
            momentAngleDegrees = currentMomentAngleDegrees(progress.remainingRatio).toDouble(),
            isFinished = progress.isFinished,
            remainingHours = progress.remainingHours,
            remainingMinutes = progress.remainingMinutes,
        )
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.DayViewCoreTest"`
Expected: PASS.

- [ ] **Step 5: Verify the facade compiles for native and lint is clean**

Run: `./gradlew :core:compileKotlinMacosArm64 ktlintCheck`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/DayViewCore.kt \
        core/src/commonTest/kotlin/fr/dayview/app/DayViewCoreTest.kt
git commit -m "feat(core): add primitives-only DayViewCore facade for native callers"
```

---

## Task 3: Assemble the `DayViewKit` XCFramework

Adds the XCFramework DSL and produces a macOS framework from `:core`. Deliverable: a built `DayViewKit.xcframework` containing both arches.

**Files:**
- Modify: `core/build.gradle.kts`

**Interfaces:**
- Produces: Gradle task `:core:assembleDayViewKitXCFramework` emitting `core/build/XCFrameworks/release/DayViewKit.xcframework`; Sync task `:core:syncXCFramework` copying it to `macos/Packages/DayViewKit/DayViewKit.xcframework`.

- [ ] **Step 1: Add the XCFramework import**

At the very top of `core/build.gradle.kts`, above the `plugins {` block, add:

```kotlin
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
```

- [ ] **Step 2: Configure framework binaries and the XCFramework**

In `core/build.gradle.kts`, replace these two lines:

```kotlin
    macosArm64()
    macosX64()
```

with:

```kotlin
    val xcf = XCFramework("DayViewKit")
    listOf(macosArm64(), macosX64()).forEach { target ->
        target.binaries.framework {
            baseName = "DayViewKit"
            xcf.add(this)
        }
    }
```

- [ ] **Step 3: Add the Sync task that publishes the framework to the Swift side**

At the end of `core/build.gradle.kts`, add:

```kotlin
val syncXCFramework by tasks.registering(Sync::class) {
    dependsOn("assembleDayViewKitReleaseXCFramework")
    from(layout.buildDirectory.dir("XCFrameworks/release/DayViewKit.xcframework"))
    into(rootProject.layout.projectDirectory.dir("macos/Packages/DayViewKit/DayViewKit.xcframework"))
}
```

- [ ] **Step 4: Assemble the XCFramework**

Run: `./gradlew :core:assembleDayViewKitXCFramework`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Verify the artifact exists with both arches**

Run: `ls core/build/XCFrameworks/release/DayViewKit.xcframework`
Expected: an `Info.plist` plus `macos-arm64` and `macos-x86_64` directories.

- [ ] **Step 6: Verify lint stays clean**

Run: `./gradlew ktlintCheck`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add core/build.gradle.kts
git commit -m "build(core): assemble DayViewKit XCFramework for macOS"
```

---

## Task 4: Wrap the XCFramework in a Swift Package and smoke-test interop

Creates the SPM binary target and a headless Swift executable that calls the facade — verifying Kotlin↔Swift interop without Xcode. Deliverable: `swift run smoke` prints values computed by `:core`.

**Files:**
- Create: `macos/Packages/DayViewKit/Package.swift`
- Create: `macos/Packages/DayViewKit/Sources/smoke/main.swift`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: `:core:syncXCFramework` output at `macos/Packages/DayViewKit/DayViewKit.xcframework`; `DayViewCore.shared.dayProgress(nowEpochMillis:startMinutes:endMinutes:)` from the framework.
- Produces: SPM library product `DayViewKit` for Task 5 to depend on.

- [ ] **Step 1: Ignore generated/synced build artifacts**

Append to `.gitignore`:

```gitignore
# macOS native (generated / synced artifacts)
macos/Packages/DayViewKit/DayViewKit.xcframework/
macos/DayView.xcodeproj/
macos/.build/
macos/Packages/DayViewKit/.build/
```

- [ ] **Step 2: Create the Swift package**

Create `macos/Packages/DayViewKit/Package.swift`:

```swift
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
```

- [ ] **Step 3: Create the smoke executable**

Create `macos/Packages/DayViewKit/Sources/smoke/main.swift`:

```swift
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
```

- [ ] **Step 4: Publish the framework to the Swift package**

Run: `./gradlew :core:syncXCFramework`
Expected: BUILD SUCCESSFUL; `macos/Packages/DayViewKit/DayViewKit.xcframework` now exists.

- [ ] **Step 5: Run the interop smoke test**

Run: `cd macos/Packages/DayViewKit && swift run smoke`
Expected: prints five lines with real numbers (e.g. `remaining=3h 0m` shape), proving the Swift process links the framework and calls the Kotlin facade. Return to repo root afterward: `cd -`.

- [ ] **Step 6: Commit**

```bash
git add .gitignore macos/Packages/DayViewKit/Package.swift \
        macos/Packages/DayViewKit/Sources/smoke/main.swift
git commit -m "feat(macos): wrap DayViewKit framework in a Swift package with interop smoke test"
```

---

## Task 5: Native SwiftUI app rendering the live ring

Generates an Xcode app via XcodeGen and draws the progress ring from the facade on a 1 Hz timer. Deliverable: a launchable macOS `.app` whose ring and remaining-time text update every second from `:core`.

**Files:**
- Create: `macos/project.yml`
- Create: `macos/DayView/DayViewApp.swift`
- Create: `macos/DayView/RingView.swift`

**Interfaces:**
- Consumes: SPM package `DayViewKit` (Task 4); `DayViewCore.shared.dayProgress(...)` returning `DayProgressSnapshot` fields `momentAngleDegrees: Double`, `isFinished: Bool`, `remainingHours: Int64`, `remainingMinutes: Int64`.

- [ ] **Step 1: Create the XcodeGen project spec**

Create `macos/project.yml`:

```yaml
name: DayView
options:
  bundleIdPrefix: fr.dayview
  deploymentTarget:
    macOS: "13.0"
packages:
  DayViewKit:
    path: Packages/DayViewKit
targets:
  DayView:
    type: application
    platform: macOS
    sources:
      - path: DayView
    dependencies:
      - package: DayViewKit
        product: DayViewKit
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: fr.dayview.app
        GENERATE_INFOPLIST_FILE: YES
        MARKETING_VERSION: "1.0.0"
        CURRENT_PROJECT_VERSION: "1"
        SWIFT_VERSION: "5.9"
        CODE_SIGN_STYLE: Automatic
        CODE_SIGNING_ALLOWED: NO
```

- [ ] **Step 2: Create the app entry point**

Create `macos/DayView/DayViewApp.swift`:

```swift
import SwiftUI

@main
struct DayViewApp: App {
    var body: some Scene {
        WindowGroup {
            RingView()
                .frame(minWidth: 420, minHeight: 680)
        }
    }
}
```

- [ ] **Step 3: Create the ring view**

Create `macos/DayView/RingView.swift`:

```swift
import SwiftUI
import DayViewKit

struct RingView: View {
    // Hardcoded working window for the walking skeleton: 09:00–18:00.
    private static let startMinutes: Int32 = 540
    private static let endMinutes: Int32 = 1080

    @State private var snapshot = RingView.currentSnapshot()
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(spacing: 24) {
            Canvas { context, size in
                let inset: CGFloat = 40
                let side = min(size.width, size.height) - inset * 2
                let center = CGPoint(x: size.width / 2, y: size.height / 2)
                let radius = side / 2
                let lineWidth: CGFloat = 18

                var track = Path()
                track.addArc(
                    center: center, radius: radius,
                    startAngle: .degrees(0), endAngle: .degrees(360),
                    clockwise: false
                )
                context.stroke(track, with: .color(.gray.opacity(0.2)), lineWidth: lineWidth)

                var sweep = Path()
                sweep.addArc(
                    center: center, radius: radius,
                    startAngle: .degrees(-90),
                    endAngle: .degrees(snapshot.momentAngleDegrees),
                    clockwise: false
                )
                context.stroke(
                    sweep,
                    with: .color(.accentColor),
                    style: StrokeStyle(lineWidth: lineWidth, lineCap: .round)
                )
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            Text(timeText)
                .font(.system(size: 44, weight: .semibold, design: .rounded))
                .monospacedDigit()
        }
        .padding(32)
        .onReceive(timer) { _ in
            snapshot = RingView.currentSnapshot()
        }
    }

    private var timeText: String {
        if snapshot.isFinished { return "Day over" }
        return String(
            format: "%dh %02dm",
            Int(snapshot.remainingHours),
            Int(snapshot.remainingMinutes)
        )
    }

    private static func currentSnapshot() -> DayProgressSnapshot {
        DayViewCore.shared.dayProgress(
            nowEpochMillis: Int64(Date().timeIntervalSince1970 * 1000),
            startMinutes: startMinutes,
            endMinutes: endMinutes
        )
    }
}
```

- [ ] **Step 4: Ensure the framework is published, then generate the Xcode project**

Run:

```bash
./gradlew :core:syncXCFramework
cd macos && xcodegen generate && cd -
```

Expected: `macos/DayView.xcodeproj` is created (`Created project at …/macos/DayView.xcodeproj`).

- [ ] **Step 5: Build the app**

Run:

```bash
xcodebuild -project macos/DayView.xcodeproj -scheme DayView \
  -configuration Debug -derivedDataPath macos/build build
```

Expected: `** BUILD SUCCEEDED **`.

If the sweep arc visually fills counter-clockwise or from the wrong origin when launched (Step 6), change the sweep `addArc` to `clockwise: true` and/or negate the angle expression — SwiftUI's y-down `Canvas` may mirror the Compose sweep direction. The target is a ring that fills clockwise starting at the top.

- [ ] **Step 6: Launch and visually verify**

Run: `open macos/build/Build/Products/Debug/DayView.app`
Expected: a window showing a ring with a colored sweep and a centered `Xh YYm` label; the label decrements and the sweep advances once per second. During 09:00–18:00 local time the ring is partially filled; outside it, it reads full or `Day over`.

- [ ] **Step 7: Commit**

```bash
git add macos/project.yml macos/DayView/DayViewApp.swift macos/DayView/RingView.swift
git commit -m "feat(macos): native SwiftUI app rendering the live day-progress ring"
```

---

## Self-Review Notes

- **Spec coverage:** `:core` module (§Architecture 1) → Task 1; Swift facade (§Architecture 2) → Task 2; XCFramework build pipeline (§Architecture 3) → Task 3; SPM binary target (§Architecture 4) → Task 4; SwiftUI ring app (§Architecture 5) → Task 5. Done criteria (assemble task, green Android/Linux gate, facade test, live `.app`) are all covered by explicit verification steps.
- **Deviations from spec, noted intentionally:** framework module renamed `DayViewCore` → `DayViewKit` (Swift name-collision avoidance, per Global Constraints); Xcode project generated via XcodeGen from a committed `project.yml` (reproducible, agent-generatable, and keeps the pbxproj out of git) rather than a hand-authored `.xcodeproj`; an extra headless `swift run smoke` interop check added in Task 4 to retire the interop risk before the GUI is involved.
- **Type consistency:** `DayProgressSnapshot` field names/types (`momentAngleDegrees: Double`, `remainingHours/remainingMinutes: Long→Int64`, `isFinished: Bool`) are consistent across Tasks 2, 4, and 5; the Kotlin `object DayViewCore` is consistently accessed as `DayViewCore.shared` in Swift.
