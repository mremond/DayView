#!/usr/bin/env bash
set -euo pipefail

# Wrap a Compose/jpackage app-image directory into a portable AppImage.
# Usage: build_appimage.sh <app-image-dir> <version> <icon-png> <out-dir>

APP_IMAGE_DIR="${1:?app-image dir required}"
VERSION="${2:?version required}"
ICON_PNG="${3:?icon png required}"
OUT_DIR="${4:?output dir required}"

if [[ ! -x "${APP_IMAGE_DIR}/bin/DayView" ]]; then
    echo "error: ${APP_IMAGE_DIR}/bin/DayView not found or not executable" >&2
    exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
APPDIR="${WORK}/DayView.AppDir"
mkdir -p "${APPDIR}"
cp -a "${APP_IMAGE_DIR}/." "${APPDIR}/"

cat > "${APPDIR}/AppRun" <<'RUN'
#!/usr/bin/env bash
HERE="$(dirname "$(readlink -f "${0}")")"
exec "${HERE}/bin/DayView" "$@"
RUN
chmod +x "${APPDIR}/AppRun"

cat > "${APPDIR}/DayView.desktop" <<'DESKTOP'
[Desktop Entry]
Type=Application
Name=DayView
Exec=DayView
Icon=DayView
Categories=Utility;
Terminal=false
DESKTOP

cp "${ICON_PNG}" "${APPDIR}/DayView.png"

mkdir -p "${OUT_DIR}"
# The leading --appimage-extract-and-run makes the appimagetool AppImage run
# without FUSE (GitHub runners have no FUSE); remaining args go to the tool.
ARCH=x86_64 appimagetool --appimage-extract-and-run \
    "${APPDIR}" "${OUT_DIR}/DayView-${VERSION}.x86_64.AppImage"

echo "built ${OUT_DIR}/DayView-${VERSION}.x86_64.AppImage"
