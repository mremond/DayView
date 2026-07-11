#!/bin/zsh

set -euo pipefail

if [[ $# -ne 2 ]]; then
    print -u2 "Usage: $0 <dmg> <volume-icon.icns>"
    exit 64
fi

dmg_path="$1"
icon_path="$2"

if [[ ! -f "$dmg_path" ]]; then
    print -u2 "DMG not found: $dmg_path"
    exit 66
fi

if [[ ! -f "$icon_path" ]]; then
    print -u2 "Volume icon not found: $icon_path"
    exit 66
fi

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/dayview-dmg.XXXXXX")"
writable_dmg="$work_dir/DayView-writable.dmg"
customized_dmg="$work_dir/DayView-customized.dmg"
mount_point="$work_dir/volume"
mounted=false

cleanup() {
    if [[ "$mounted" == true ]]; then
        hdiutil detach "$mount_point" -force >/dev/null 2>&1 || true
    fi
    rm -rf "$work_dir"
}
trap cleanup EXIT

mkdir -p "$mount_point"
hdiutil convert "$dmg_path" -quiet -format UDRW -o "$writable_dmg"
hdiutil attach "$writable_dmg" -quiet -readwrite -owners off -noverify -noautoopen -mountpoint "$mount_point"
mounted=true

if [[ -e "$mount_point/.VolumeIcon.icns" ]]; then
    chmod u+w "$mount_point/.VolumeIcon.icns"
fi
cp "$icon_path" "$mount_point/.VolumeIcon.icns"
if [[ ! -e "$mount_point/Applications" ]]; then
    ln -s /Applications "$mount_point/Applications"
fi

# Finder only uses .VolumeIcon.icns when the volume has its custom-icon bit set.
xcrun SetFile -a C "$mount_point"

hdiutil detach "$mount_point" -quiet
mounted=false
hdiutil convert "$writable_dmg" -quiet -format UDZO -imagekey zlib-level=9 -o "$customized_dmg"
mv "$customized_dmg" "$dmg_path"
