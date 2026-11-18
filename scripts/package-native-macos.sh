#!/usr/bin/env bash
# Build, sign, notarize and package the native SwiftUI macOS app as dist/DayView-<version>.dmg.
#
#     scripts/package-native-macos.sh <version> [--skip-notarize]
#
# Configuration (local.properties keys, overridable by environment):
#   dayview.macos.signingIdentity  / DAYVIEW_SIGNING_IDENTITY  Developer ID Application identity
#   dayview.macos.notaryProfile    / DAYVIEW_NOTARY_PROFILE    notarytool keychain profile
#                                                              (default: dayview-notary)
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
macos_dir="$repo_root/macos"
derived="$macos_dir/build/native-release"
dist="$repo_root/dist"

usage() { echo "usage: $0 <version, e.g. 1.4.0> [--skip-notarize]" >&2; exit 2; }
fail() { echo "error: $*" >&2; exit 1; }
phase() { echo; echo "==> $*"; }

version=""
skip_notarize=0
for arg in "$@"; do
  case "$arg" in
    --skip-notarize) skip_notarize=1 ;;
    -*) usage ;;
    *) [ -z "$version" ] && version="$arg" || usage ;;
  esac
done
[ -n "$version" ] || usage
[[ "$version" =~ ^[0-9]+(\.[0-9]+){1,2}$ ]] || fail "version must be numeric, like 1.4.0 (got '$version')"

prop() {
  local file="$repo_root/local.properties"
  [ -f "$file" ] || return 0
  sed -n "s/^$1[[:space:]]*=[[:space:]]*//p" "$file" | head -n 1
}

identity="${DAYVIEW_SIGNING_IDENTITY:-$(prop dayview.macos.signingIdentity)}"
profile="${DAYVIEW_NOTARY_PROFILE:-$(prop dayview.macos.notaryProfile)}"
profile="${profile:-dayview-notary}"
build_number="$(git -C "$repo_root" rev-list --count HEAD)"

stage=""
cleanup() { [ -n "$stage" ] && rm -rf "$stage"; return 0; }
trap cleanup EXIT

phase "Preflight"
[ -n "$identity" ] || fail "no signing identity. Set dayview.macos.signingIdentity in local.properties (or DAYVIEW_SIGNING_IDENTITY)."
security find-identity -v -p codesigning | grep -qF "\"$identity\"" \
  || fail "signing identity not found in the keychain: $identity (see: security find-identity -v -p codesigning)"
command -v xcodegen >/dev/null 2>&1 || fail "xcodegen not found. Install it with: brew install xcodegen"
command -v xcodebuild >/dev/null 2>&1 || fail "xcodebuild not found. Install Xcode."
if [ "$skip_notarize" -eq 0 ]; then
  xcrun notarytool history --keychain-profile "$profile" >/dev/null 2>&1 \
    || fail "notarytool profile '$profile' not found or invalid. Create it once with:
  xcrun notarytool store-credentials $profile --apple-id <id> --team-id <team> --password <app-specific password>
or pass --skip-notarize."
fi
echo "version $version (build $build_number), identity: $identity"

phase "Syncing the DayViewKit XCFramework"
(cd "$repo_root" && ./gradlew :core:syncXCFramework)

phase "Generating the Xcode project"
(cd "$macos_dir" && xcodegen generate)

phase "Building DayView.app (Release)"
xcodebuild -project "$macos_dir/DayView.xcodeproj" -scheme DayView -configuration Release \
  -destination 'platform=macOS' -derivedDataPath "$derived" \
  MARKETING_VERSION="$version" CURRENT_PROJECT_VERSION="$build_number" \
  CODE_SIGN_IDENTITY="$identity" -quiet build
app="$derived/Build/Products/Release/DayView.app"
[ -d "$app" ] || fail "build product not found: $app"

phase "Verifying the app signature"
codesign --verify --deep --strict --verbose=2 "$app"
entitlements="$(codesign -d --entitlements - --xml "$app" 2>/dev/null)"
echo "$entitlements" | grep -q "com.apple.security.personal-information.calendars" \
  || fail "calendar entitlement missing from the signed app"
echo "$entitlements" | grep -q "com.apple.security.app-sandbox" && fail "app unexpectedly sandboxed"
codesign -d --verbose=2 "$app" 2>&1 | grep -E "flags=|Authority=Developer ID Application|Timestamp" || true

phase "Staging the disk image"
stage="$(mktemp -d)"
ditto "$app" "$stage/DayView.app"
ln -s /Applications "$stage/Applications"

phase "Creating the DMG"
mkdir -p "$dist"
dmg="$dist/DayView-$version.dmg"
rm -f "$dmg"
hdiutil create -volname "DayView" -srcfolder "$stage" -format UDZO -ov "$dmg" >/dev/null
codesign --sign "$identity" --timestamp "$dmg"
codesign --verify --verbose=2 "$dmg"

if [ "$skip_notarize" -eq 1 ]; then
  phase "Skipping notarization (--skip-notarize): the DMG is signed but not notarized"
else
  phase "Notarizing (this can take a few minutes)"
  result="$(xcrun notarytool submit "$dmg" --keychain-profile "$profile" --wait --output-format plist)"
  status="$(/usr/libexec/PlistBuddy -c 'Print :status' /dev/stdin <<<"$result")"
  sub_id="$(/usr/libexec/PlistBuddy -c 'Print :id' /dev/stdin <<<"$result")"
  if [ "$status" != "Accepted" ]; then
    echo "notarization status: $status" >&2
    echo "details: xcrun notarytool log $sub_id --keychain-profile $profile" >&2
    exit 1
  fi

  phase "Stapling"
  xcrun stapler staple "$dmg"

  phase "Gatekeeper assessments"
  spctl --assess --type execute --verbose=2 "$app"
  spctl --assess --type open --context context:primary-signature --verbose=2 "$dmg"
fi

phase "Done"
echo "$dmg"
