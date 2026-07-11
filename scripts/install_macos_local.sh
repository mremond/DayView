#!/bin/zsh

set -euo pipefail

if [[ $# -ne 1 ]]; then
    print -u2 "Usage: $0 <dmg>"
    exit 64
fi

dmg_path="$1"
app_name="DayView.app"
dest_dir="/Applications"

if [[ ! -f "$dmg_path" ]]; then
    print -u2 "DMG not found: $dmg_path"
    exit 66
fi

mount_point="$(mktemp -d "${TMPDIR:-/tmp}/dayview-install.XXXXXX")"
mounted=false

cleanup() {
    if [[ "$mounted" == true ]]; then
        hdiutil detach "$mount_point" -force >/dev/null 2>&1 || true
    fi
    rmdir "$mount_point" 2>/dev/null || true
}
trap cleanup EXIT

hdiutil attach "$dmg_path" -quiet -readonly -nobrowse -noautoopen -mountpoint "$mount_point"
mounted=true

if [[ ! -d "$mount_point/$app_name" ]]; then
    print -u2 "$app_name not found inside $dmg_path"
    exit 66
fi

# Replace the installed bundle atomically enough for a local install: drop the old
# copy, then copy the freshly packaged one in.
rm -rf "$dest_dir/$app_name"
cp -R "$mount_point/$app_name" "$dest_dir/"

print "Installed $app_name to $dest_dir"
