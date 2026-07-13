#!/usr/bin/env bash
# Build and launch the native SwiftUI macOS app.
#
# Assumes the DayViewKit XCFramework has already been synced into the Swift
# package — the Gradle `:core:runMacNative` task depends on `:core:syncXCFramework`,
# which does that. Invoke through Gradle rather than directly:
#
#     ./gradlew :core:runMacNative
#
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
macos_dir="$repo_root/macos"
app="$macos_dir/build/Build/Products/Debug/DayView.app"

if ! command -v xcodegen >/dev/null 2>&1; then
  echo "error: xcodegen not found. Install it with: brew install xcodegen" >&2
  exit 1
fi

echo "==> Generating Xcode project from macos/project.yml"
(cd "$macos_dir" && xcodegen generate)

echo "==> Building DayView.app"
xcodebuild -project "$macos_dir/DayView.xcodeproj" -scheme DayView \
  -configuration Debug -derivedDataPath "$macos_dir/build" build

echo "==> Launching $app"
open "$app"
